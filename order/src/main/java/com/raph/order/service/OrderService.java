package com.raph.order.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.raph.order.dto.CreateOrderRequest;
import com.raph.order.dto.OrderItemRequest;
import com.raph.order.dto.StockLockAdjustRequest;
import com.raph.order.dto.UpdateOrderRequest;
import com.raph.order.entity.Order;
import com.raph.order.entity.OrderItem;
import com.raph.order.repository.OrderRepository;

@Service
public class OrderService {

    private static final DateTimeFormatter ORDER_NO_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderRepository orderRepository;
    private final RestTemplate restTemplate;

    @Value("${services.stock.base-url}")
    private String stockServiceBaseUrl;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
        this.restTemplate = new RestTemplate();
    }

    public List<Order> queryOrders(Long userId) {
        return orderRepository.queryOrders(userId);
    }

    public Optional<Order> findById(Long id) {
        return orderRepository.findWithItemsById(id);
    }

    @Transactional
    public Order create(CreateOrderRequest request) {
        validateCreateRequest(request);

        LocalDateTime now = LocalDateTime.now();
        Order order = new Order();
        order.setId(generateOrderId());
        order.setOrderNo(generateOrderNo(now));
        order.setUserId(request.getUserId());
        order.setType(defaultIfNull(request.getType(), 0));
        order.setOrderStatus(defaultIfNull(request.getOrderStatus(), 0));
        order.setPayStatus(defaultIfNull(request.getPayStatus(), 0));
        order.setCreateTime(now);
        order.setUpdateTime(now);
        order.setExpireTime(request.getExpireTime() == null ? now.plusMinutes(30) : request.getExpireTime());

        if (order.getPayStatus() != null && order.getPayStatus() == 1) {
            order.setPayTime(now);
        }

        Map<Long, Integer> quantityByGoods = buildQuantityMapFromRequests(request.getItems());
        adjustStockWithDelta(quantityByGoods);

        List<OrderItem> items = buildItems(request.getItems(), order, now);
        order.setItems(items);
        order.setAmount(calculateOrderAmount(items));

        return orderRepository.save(order);
    }

    @Transactional
    public Order update(Long id, UpdateOrderRequest request) {
        Order existing = orderRepository.findWithItemsById(id)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));

        LocalDateTime now = LocalDateTime.now();

        if (request.getType() != null) {
            existing.setType(request.getType());
        }
        if (request.getOrderStatus() != null) {
            existing.setOrderStatus(request.getOrderStatus());
        }
        if (request.getPayStatus() != null) {
            existing.setPayStatus(request.getPayStatus());
            if (request.getPayStatus() == 1 && existing.getPayTime() == null) {
                existing.setPayTime(now);
            }
        }
        if (request.getExpireTime() != null) {
            existing.setExpireTime(request.getExpireTime());
        }

        if (request.getItems() != null) {
            if (request.getItems().isEmpty()) {
                throw new IllegalArgumentException("订单明细不能为空");
            }

            Map<Long, Integer> oldQuantityByGoods = buildQuantityMapFromOrderItems(existing.getItems());
            Map<Long, Integer> newQuantityByGoods = buildQuantityMapFromRequests(request.getItems());
            Map<Long, Integer> quantityDeltaByGoods = buildQuantityDeltaMap(oldQuantityByGoods, newQuantityByGoods);
            adjustStockWithDelta(quantityDeltaByGoods);

            existing.getItems().clear();
            List<OrderItem> newItems = buildItems(request.getItems(), existing, now);
            existing.getItems().addAll(newItems);
            existing.setAmount(calculateOrderAmount(existing.getItems()));
        }

        existing.setUpdateTime(now);
        return orderRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        Order existing = orderRepository.findWithItemsById(id)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));

        Map<Long, Integer> lockedQuantityByGoods = buildQuantityMapFromOrderItems(existing.getItems());
        Map<Long, Integer> releaseDeltaByGoods = new HashMap<>();
        for (Map.Entry<Long, Integer> entry : lockedQuantityByGoods.entrySet()) {
            releaseDeltaByGoods.put(entry.getKey(), -entry.getValue());
        }

        adjustStockWithDelta(releaseDeltaByGoods);
        orderRepository.delete(existing);
    }

    private void validateCreateRequest(CreateOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (request.getUserId() == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("订单明细不能为空");
        }
    }

    private List<OrderItem> buildItems(List<OrderItemRequest> itemRequests, Order order, LocalDateTime now) {
        List<OrderItem> items = new ArrayList<>();

        for (OrderItemRequest itemRequest : itemRequests) {
            validateItem(itemRequest);

            BigDecimal itemAmount = itemRequest.getBuyPrice()
                    .multiply(BigDecimal.valueOf(itemRequest.getQuantity()))
                    .setScale(2, RoundingMode.HALF_UP);

            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setGoodsId(itemRequest.getGoodsId());
            item.setBuyPrice(itemRequest.getBuyPrice().setScale(2, RoundingMode.HALF_UP));
            item.setQuantity(itemRequest.getQuantity());
            item.setItemAmount(itemAmount);
            item.setActivityId(itemRequest.getActivityId());
            item.setCreateTime(now);

            items.add(item);
        }

        return items;
    }

    private void validateItem(OrderItemRequest itemRequest) {
        if (itemRequest == null) {
            throw new IllegalArgumentException("订单明细项不能为空");
        }
        if (itemRequest.getGoodsId() == null) {
            throw new IllegalArgumentException("goodsId 不能为空");
        }
        if (itemRequest.getBuyPrice() == null || itemRequest.getBuyPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("buyPrice 不能为空且必须大于等于 0");
        }
        if (itemRequest.getQuantity() == null || itemRequest.getQuantity() <= 0) {
            throw new IllegalArgumentException("quantity 必须大于 0");
        }
    }

    private BigDecimal calculateOrderAmount(List<OrderItem> items) {
        return items.stream()
                .map(OrderItem::getItemAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private long generateOrderId() {
        long millis = System.currentTimeMillis();
        long random = ThreadLocalRandom.current().nextLong(1000L, 9999L);
        return millis * 10000 + random;
    }

    private String generateOrderNo(LocalDateTime now) {
        long random = ThreadLocalRandom.current().nextLong(1000L, 9999L);
        return "ORD" + now.format(ORDER_NO_TIME_FORMATTER) + random;
    }

    private Integer defaultIfNull(Integer value, Integer defaultValue) {
        return value == null ? defaultValue : value;
    }

    private Map<Long, Integer> buildQuantityMapFromRequests(List<OrderItemRequest> itemRequests) {
        Map<Long, Integer> quantityByGoods = new HashMap<>();
        for (OrderItemRequest itemRequest : itemRequests) {
            validateItem(itemRequest);
            quantityByGoods.merge(itemRequest.getGoodsId(), itemRequest.getQuantity(), Integer::sum);
        }
        return quantityByGoods;
    }

    private Map<Long, Integer> buildQuantityMapFromOrderItems(List<OrderItem> items) {
        Map<Long, Integer> quantityByGoods = new HashMap<>();
        for (OrderItem item : items) {
            quantityByGoods.merge(item.getGoodsId(), item.getQuantity(), Integer::sum);
        }
        return quantityByGoods;
    }

    private Map<Long, Integer> buildQuantityDeltaMap(Map<Long, Integer> oldQuantityByGoods,
                                                     Map<Long, Integer> newQuantityByGoods) {
        Map<Long, Integer> deltaMap = new HashMap<>();

        for (Map.Entry<Long, Integer> entry : oldQuantityByGoods.entrySet()) {
            deltaMap.put(entry.getKey(), -entry.getValue());
        }

        for (Map.Entry<Long, Integer> entry : newQuantityByGoods.entrySet()) {
            deltaMap.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }

        deltaMap.entrySet().removeIf(entry -> entry.getValue() == 0);
        return deltaMap;
    }

    // delta > 0 表示扣减可用库存并增加锁定库存，delta < 0 表示释放锁定库存并增加可用库存
    private void adjustStockWithDelta(Map<Long, Integer> quantityDeltaByGoods) {
        if (quantityDeltaByGoods == null || quantityDeltaByGoods.isEmpty()) {
            return;
        }

        String url = stockServiceBaseUrl + "/api/stock/adjust-lock";
        StockLockAdjustRequest request = new StockLockAdjustRequest();
        request.setQuantityDeltaByGoods(quantityDeltaByGoods);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(request), Map.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalArgumentException("库存服务调用失败");
            }
        } catch (HttpStatusCodeException ex) {
            String body = ex.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                throw new IllegalArgumentException("库存服务错误: " + body);
            }
            throw new IllegalArgumentException("库存服务错误: " + ex.getStatusCode());
        } catch (Exception ex) {
            throw new IllegalArgumentException("库存服务调用异常: " + ex.getMessage());
        }
    }
}

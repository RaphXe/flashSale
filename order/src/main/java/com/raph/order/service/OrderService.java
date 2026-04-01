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
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.raph.order.dto.CreateOrderRequest;
import com.raph.order.dto.GoodsDetailResponse;
import com.raph.order.dto.OrderItemRequest;
import com.raph.order.dto.StockLockAdjustRequest;
import com.raph.order.dto.UpdateOrderRequest;
import com.raph.order.entity.Order;
import com.raph.order.entity.OrderItem;
import com.raph.order.repository.OrderRepository;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;

@Service
public class OrderService {

    private static final DateTimeFormatter ORDER_NO_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderRepository orderRepository;
    private final RestTemplate restTemplate;
    private final Snowflake snowflake;

    @Value("${services.goods.base-url}")
    private String goodsServiceBaseUrl;

    @Value("${services.stock.base-url}")
    private String stockServiceBaseUrl;

    public OrderService(OrderRepository orderRepository,
                        @Value("${snowflake.worker-id:1}") long workerId,
                        @Value("${snowflake.datacenter-id:1}") long datacenterId) {
        this.orderRepository = orderRepository;
        this.restTemplate = new RestTemplate();
        this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
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
        // 从goods服务中获取价格信息并校验商品状态，构建一个以goodsId为key，price为value的Map，会抛出illegalArgumentException
        Map<Long, BigDecimal> priceByGoods = loadGoodsPriceById(request.getItems());
        // 向stock服务接口调用调整库存锁定，扣减可用库存并增加锁定库存，如果接口调用失败会抛出异常导致订单创建失败，事务回滚，避免订单和库存数据不一致
        adjustStockWithDelta(quantityByGoods);

        List<OrderItem> items = buildItems(request.getItems(), order, now, priceByGoods);
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
            Map<Long, BigDecimal> priceByGoods = loadGoodsPriceById(request.getItems());
            Map<Long, Integer> quantityDeltaByGoods = buildQuantityDeltaMap(oldQuantityByGoods, newQuantityByGoods);
            adjustStockWithDelta(quantityDeltaByGoods);

            existing.getItems().clear();
            List<OrderItem> newItems = buildItems(request.getItems(), existing, now, priceByGoods);
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

    // 根据订单请求中的明细项列表构建订单明细实体列表，过程中会校验每个明细项的合法性，并使用从goods服务获取的价格信息计算每个明细项的金额
    private List<OrderItem> buildItems(List<OrderItemRequest> itemRequests, Order order, LocalDateTime now,
                                       Map<Long, BigDecimal> priceByGoods) {
        List<OrderItem> items = new ArrayList<>();

        for (OrderItemRequest itemRequest : itemRequests) {
            validateItem(itemRequest);
            // 从goods服务获取的价格信息是可信的，不能使用订单请求中传过来的价格信息进行计算，以防止恶意篡改价格导致金额计算错误
            BigDecimal trustedPrice = priceByGoods.get(itemRequest.getGoodsId());
            if (trustedPrice == null) {
                throw new IllegalArgumentException("商品价格不存在，goodsId=" + itemRequest.getGoodsId());
            }

            BigDecimal itemAmount = trustedPrice
                    .multiply(BigDecimal.valueOf(itemRequest.getQuantity()))
                    .setScale(2, RoundingMode.HALF_UP);

            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setGoodsId(itemRequest.getGoodsId());
            item.setBuyPrice(trustedPrice);
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
        return snowflake.nextId();
    }

    // 根据全段传输的商品ID列表批量调用goods服务接口获取价格和状态等信息，并进行校验，返回一个以goodsId为key，price为value的Map
    private Map<Long, BigDecimal> loadGoodsPriceById(List<OrderItemRequest> itemRequests) {
        Set<Long> goodsIds = itemRequests.stream()
                .map(OrderItemRequest::getGoodsId)
                .collect(Collectors.toSet());

        // 调用goods服务接口批量获取商品信息，接口设计为POST /api/goods/batch，参数为商品ID列表，返回值为商品信息列表
        String url = goodsServiceBaseUrl + "/api/goods/batch";
        try {
            ResponseEntity<List<GoodsDetailResponse>> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(new ArrayList<>(goodsIds)),
                    new ParameterizedTypeReference<List<GoodsDetailResponse>>() {
                    }
            );

            List<GoodsDetailResponse> goodsList = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || goodsList == null) {
                throw new IllegalArgumentException("商品服务批量调用失败");
            }

            Set<Long> returnedGoodsIds = goodsList.stream()
                    .map(GoodsDetailResponse::getId)
                    .collect(Collectors.toSet());
            // 校验前端传入的商品ID列表  
            for (Long goodsId : goodsIds) {
                if (!returnedGoodsIds.contains(goodsId)) {
                    throw new IllegalArgumentException("商品不存在，goodsId=" + goodsId);
                }
            }

            // 进一步校验商品细节
            Map<Long, BigDecimal> priceByGoods = new HashMap<>();
            for (GoodsDetailResponse goods : goodsList) {
                if (goods.getId() == null) {
                    throw new IllegalArgumentException("商品数据非法: 缺少id");
                }
                if (goods.getStatus() == null || goods.getStatus() != 1) {
                    throw new IllegalArgumentException("商品不可售，goodsId=" + goods.getId());
                }
                if (goods.getPrice() == null || goods.getPrice().compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException("商品价格非法，goodsId=" + goods.getId());
                }
                priceByGoods.put(goods.getId(), goods.getPrice().setScale(2, RoundingMode.HALF_UP));
            }
            return priceByGoods;
        } catch (HttpStatusCodeException ex) {
            String body = ex.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                throw new IllegalArgumentException("商品服务错误: " + body);
            }
            throw new IllegalArgumentException("商品服务错误: " + ex.getStatusCode());
        } catch (RestClientException ex) {
            throw new IllegalArgumentException("商品服务调用异常: " + ex.getMessage());
        }
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

    private Map<Long, Integer> buildQuantityDeltaMap(Map<Long, Integer> oldQuantityByGoods, Map<Long, Integer> newQuantityByGoods) {
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

    // 调用stock服务接口调整库存锁定，delta > 0 表示扣减可用库存并增加锁定库存，delta < 0 表示释放锁定库存并增加可用库存
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
        } catch (RestClientException ex) {
            throw new IllegalArgumentException("库存服务调用异常: " + ex.getMessage());
        }
    }
}

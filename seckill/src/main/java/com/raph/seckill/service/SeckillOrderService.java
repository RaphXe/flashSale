package com.raph.seckill.service;

import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.raph.seckill.dto.CreateSeckillOrderRequest;
import com.raph.seckill.dto.UpdateSeckillOrderRequest;
import com.raph.seckill.entity.SeckillActivity;
import com.raph.seckill.entity.SeckillGoods;
import com.raph.seckill.entity.SeckillOrder;
import com.raph.seckill.repository.SeckillOrderRepository;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;

@Service
public class SeckillOrderService {

    private static final DateTimeFormatter ORDER_NO_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int ORDER_STATUS_PENDING = 0;
    private static final int ORDER_STATUS_CREATED = 1;
    private static final int ORDER_STATUS_TIMEOUT = 2;
    private static final int ORDER_STATUS_CANCELED = 3;

    private final SeckillOrderRepository seckillOrderRepository;
    private final SeckillGoodsService seckillGoodsService;
    private final SeckillActivityService seckillActivityService;
    private final Snowflake snowflake;

    public SeckillOrderService(SeckillOrderRepository seckillOrderRepository,
                               SeckillGoodsService seckillGoodsService,
                               SeckillActivityService seckillActivityService,
                               @Value("${snowflake.worker-id:1}") long workerId,
                               @Value("${snowflake.datacenter-id:1}") long datacenterId) {
        this.seckillOrderRepository = seckillOrderRepository;
        this.seckillGoodsService = seckillGoodsService;
        this.seckillActivityService = seckillActivityService;
        this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
    }

    public List<SeckillOrder> queryOrders(Long userId, Long activityId, Integer status) {
        if (userId != null) {
            if (status != null) {
                return seckillOrderRepository.findByUserIdAndStatusOrderByCreateTimeDesc(userId, status);
            }
            return seckillOrderRepository.findByUserIdOrderByCreateTimeDesc(userId);
        }

        if (activityId != null) {
            if (status != null) {
                return seckillOrderRepository.findByActivityIdAndStatusOrderByCreateTimeDesc(activityId, status);
            }
            return seckillOrderRepository.findByActivityIdOrderByCreateTimeDesc(activityId);
        }

        if (status != null) {
            return seckillOrderRepository.findByStatusOrderByCreateTimeDesc(status);
        }

        return seckillOrderRepository.findAllByOrderByCreateTimeDesc();
    }

    public Optional<SeckillOrder> findById(Long id) {
        return seckillOrderRepository.findById(id);
    }

    public Optional<SeckillOrder> findByOrderNo(String seckillOrderNo) {
        if (!StringUtils.hasText(seckillOrderNo)) {
            return Optional.empty();
        }
        return seckillOrderRepository.findBySeckillOrderNo(seckillOrderNo.trim());
    }

    @Transactional
    public SeckillOrder create(CreateSeckillOrderRequest request) {
        return createSync(request);
    }

    @Transactional
    public SeckillOrder createSync(CreateSeckillOrderRequest request) {
        return createSync(request, null);
    }

    @Transactional
    public SeckillOrder createSync(CreateSeckillOrderRequest request, String predefinedOrderNo) {
        validateCreateRequest(request);

        // 校验活动是否可下单
        SeckillActivity activity = validateAndGetActiveActivity(request.getActivityId());

        SeckillGoods seckillGoods = seckillGoodsService
                .findByActivityIdAndGoodsId(request.getActivityId(), request.getGoodsId())
                .orElseThrow(() -> new IllegalArgumentException("秒杀商品不存在"));

        // 下单价格必须与秒杀商品当前价格一致
        validateOrderPrice(request, seckillGoods);

        // 校验活动限购：同一用户在同一活动下累计下单数量不能超过 limitPerPerson
        validateActivityLimit(activity, request);

        // 锁定库存
        seckillGoodsService.lockStockForOrder(request.getActivityId(), request.getGoodsId(), request.getQuantity());
        
        try {
            SeckillOrder order = buildOrderForCreate(request, predefinedOrderNo);

            return seckillOrderRepository.save(order);
        } catch (RuntimeException ex) {
            try {
                // 创建订单失败，回滚锁定库存
                seckillGoodsService.releaseLockedStockForOrder(request.getActivityId(), request.getGoodsId(), request.getQuantity());
            } catch (RuntimeException rollbackEx) {
                ex.addSuppressed(rollbackEx);
            }
            throw ex;
        }
    }


    // 若订单到期，则关闭订单并释放锁定的库存
    @Transactional
    public boolean expireOrderIfPending(String seckillOrderNo) {
        if (!StringUtils.hasText(seckillOrderNo)) {
            return false;
        }

        SeckillOrder order = seckillOrderRepository.findBySeckillOrderNoForUpdate(seckillOrderNo.trim())
                .orElse(null);
        if (order == null) {
            return false;
        }

        if (order.getStatus() == null || order.getStatus() != ORDER_STATUS_PENDING) {
            return false;
        }

        if (order.getExpireTime() != null && order.getExpireTime().isAfter(LocalDateTime.now())) {
            return false;
        }

        seckillGoodsService.releaseLockedStockForOrder(order.getActivityId(), order.getGoodsId(), order.getQuantity());

        order.setStatus(ORDER_STATUS_TIMEOUT);
        order.setUpdateTime(LocalDateTime.now());
        seckillOrderRepository.save(order);
        return true;
    }

    // 活动结算时，强制取消仍处于待支付状态的秒杀订单，并释放锁定库存。
    @Transactional
    public int forceCancelPendingOrdersByActivity(Long activityId) {
        if (activityId == null) {
            return 0;
        }

        List<SeckillOrder> pendingOrders = seckillOrderRepository
                .findByActivityIdAndStatusOrderByCreateTimeDesc(activityId, ORDER_STATUS_PENDING);
        int affected = 0;
        for (SeckillOrder pendingOrder : pendingOrders) {
            if (pendingOrder == null || !StringUtils.hasText(pendingOrder.getSeckillOrderNo())) {
                continue;
            }

            SeckillOrder order = seckillOrderRepository
                    .findBySeckillOrderNoForUpdate(pendingOrder.getSeckillOrderNo())
                    .orElse(null);
            if (order == null || order.getStatus() == null || order.getStatus() != ORDER_STATUS_PENDING) {
                continue;
            }

            seckillGoodsService.releaseLockedStockForOrder(order.getActivityId(), order.getGoodsId(), order.getQuantity());

            order.setStatus(ORDER_STATUS_CANCELED);
            order.setUpdateTime(LocalDateTime.now());
            seckillOrderRepository.save(order);
            affected++;
        }
        return affected;
    }

    public void validateCreateRequestPayload(CreateSeckillOrderRequest request) {
        validateCreateRequest(request);
    }

    public String allocateOrderNo() {
        return generateOrderNo(LocalDateTime.now());
    }

    @Transactional
    public SeckillOrder update(Long id, UpdateSeckillOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }

        SeckillOrder existing = seckillOrderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("秒杀订单不存在"));

        if (request.getStatus() != null) {
            existing.setStatus(request.getStatus());
        }
        if (request.getOrderId() != null) {
            existing.setOrderId(request.getOrderId());
        }
        if (request.getExpireTime() != null) {
            existing.setExpireTime(request.getExpireTime());
        }
        existing.setUpdateTime(LocalDateTime.now());

        return seckillOrderRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        if (!seckillOrderRepository.existsById(id)) {
            throw new IllegalArgumentException("秒杀订单不存在");
        }
        seckillOrderRepository.deleteById(id);
    }

    private void validateCreateRequest(CreateSeckillOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (request.getActivityId() == null) {
            throw new IllegalArgumentException("activityId 不能为空");
        }
        if (request.getGoodsId() == null) {
            throw new IllegalArgumentException("goodsId 不能为空");
        }
        if (request.getUserId() == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new IllegalArgumentException("quantity 必须大于 0");
        }
        if (request.getSeckillPrice() == null || request.getSeckillPrice().signum() < 0) {
            throw new IllegalArgumentException("seckillPrice 非法");
        }
    }

    private SeckillActivity validateAndGetActiveActivity(Long activityId) {
        SeckillActivity activity = seckillActivityService.findById(activityId)
                .orElseThrow(() -> new IllegalArgumentException("秒杀活动不存在"));

        LocalDateTime now = LocalDateTime.now();

        // 仅验证时间，避免状态切换延时
        if (activity.getStartTime() == null || activity.getEndTime() == null
                || now.isBefore(activity.getStartTime()) || now.isAfter(activity.getEndTime())) {
            throw new IllegalArgumentException("当前不在秒杀活动时间范围内");
        }
        return activity;
    }

    private void validateOrderPrice(CreateSeckillOrderRequest request, SeckillGoods seckillGoods) {
        if (seckillGoods.getSeckillPrice() == null) {
            throw new IllegalArgumentException("秒杀商品价格未配置");
        }

        if (request.getSeckillPrice().setScale(2, RoundingMode.HALF_UP)
                .compareTo(seckillGoods.getSeckillPrice().setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw new IllegalArgumentException("秒杀价格已变更，请刷新后重试");
        }
    }

    private void validateActivityLimit(SeckillActivity activity, CreateSeckillOrderRequest request) {
        Integer limitPerPerson = activity.getLimitPerPerson();
        if (limitPerPerson == null) {
            return;
        }

        long alreadyOrdered = seckillOrderRepository.findByActivityIdOrderByCreateTimeDesc(request.getActivityId())
                .stream()
                .filter(order -> request.getUserId().equals(order.getUserId()))
                .filter(order -> Arrays.asList(ORDER_STATUS_PENDING, ORDER_STATUS_CREATED).contains(order.getStatus()))
            .mapToLong(order -> {
                Integer quantity = order.getQuantity();
                return quantity == null ? 0L : quantity.longValue();
            }).sum();

        long total = alreadyOrdered + request.getQuantity();
        if (total > limitPerPerson) {
            throw new IllegalArgumentException("超过活动限购数量");
        }
    }

    private Integer defaultIfNull(Integer value, Integer defaultValue) {
        return value == null ? defaultValue : value;
    }

    private SeckillOrder buildOrderForCreate(CreateSeckillOrderRequest request, String predefinedOrderNo) {
        LocalDateTime now = LocalDateTime.now();
        SeckillOrder order = new SeckillOrder();
        order.setId(generateId());
        order.setSeckillOrderNo(StringUtils.hasText(predefinedOrderNo)
                ? predefinedOrderNo.trim()
                : generateOrderNo(now));
        order.setActivityId(request.getActivityId());
        order.setGoodsId(request.getGoodsId());
        order.setUserId(request.getUserId());
        order.setQuantity(request.getQuantity());
        order.setSeckillPrice(request.getSeckillPrice().setScale(2, RoundingMode.HALF_UP));
        order.setAmount(request.getSeckillPrice()
                .multiply(java.math.BigDecimal.valueOf(request.getQuantity()))
                .setScale(2, RoundingMode.HALF_UP));
        order.setStatus(defaultIfNull(request.getStatus(), ORDER_STATUS_PENDING));
        order.setOrderId(null);
        order.setExpireTime(now.plusMinutes(15));
        order.setCreateTime(now);
        order.setUpdateTime(now);
        return order;
    }

    private long generateId() {
        return snowflake.nextId();
    }

    private String generateOrderNo(LocalDateTime now) {
        long random = ThreadLocalRandom.current().nextLong(1000L, 9999L);
        return "SCK" + now.format(ORDER_NO_TIME_FORMATTER) + random;
    }
}

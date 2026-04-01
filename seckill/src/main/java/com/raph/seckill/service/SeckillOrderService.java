package com.raph.seckill.service;

import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.raph.seckill.dto.CreateSeckillOrderRequest;
import com.raph.seckill.dto.UpdateSeckillOrderRequest;
import com.raph.seckill.entity.SeckillGoods;
import com.raph.seckill.entity.SeckillOrder;
import com.raph.seckill.repository.SeckillGoodsRepository;
import com.raph.seckill.repository.SeckillOrderRepository;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;

@Service
public class SeckillOrderService {

    private static final DateTimeFormatter ORDER_NO_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SeckillOrderRepository seckillOrderRepository;
    private final SeckillGoodsRepository seckillGoodsRepository;
    private final Snowflake snowflake;

    public SeckillOrderService(SeckillOrderRepository seckillOrderRepository,
                               SeckillGoodsRepository seckillGoodsRepository,
                               @Value("${snowflake.worker-id:1}") long workerId,
                               @Value("${snowflake.datacenter-id:1}") long datacenterId) {
        this.seckillOrderRepository = seckillOrderRepository;
        this.seckillGoodsRepository = seckillGoodsRepository;
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

    @Transactional
    public SeckillOrder create(CreateSeckillOrderRequest request) {
        validateCreateRequest(request);

        SeckillGoods seckillGoods = seckillGoodsRepository
                .findByActivityIdAndGoodsIdForUpdate(request.getActivityId(), request.getGoodsId())
                .orElseThrow(() -> new IllegalArgumentException("秒杀商品不存在"));

        validateAndLockSeckillGoodsStock(seckillGoods, request.getQuantity());

        seckillOrderRepository.findByActivityIdAndGoodsIdAndUserId(request.getActivityId(), request.getGoodsId(), request.getUserId())
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("该用户已存在同活动同商品的秒杀订单");
                });

        seckillGoods.setUpdateTime(LocalDateTime.now());
        seckillGoodsRepository.save(seckillGoods);

        LocalDateTime now = LocalDateTime.now();

        SeckillOrder order = new SeckillOrder();
        order.setId(generateId());
        order.setSeckillOrderNo(generateOrderNo(now));
        order.setActivityId(request.getActivityId());
        order.setGoodsId(request.getGoodsId());
        order.setUserId(request.getUserId());
        order.setQuantity(request.getQuantity());
        order.setSeckillPrice(request.getSeckillPrice().setScale(2, RoundingMode.HALF_UP));
        order.setAmount(request.getSeckillPrice()
                .multiply(java.math.BigDecimal.valueOf(request.getQuantity()))
                .setScale(2, RoundingMode.HALF_UP));
        order.setStatus(defaultIfNull(request.getStatus(), 0));
        order.setOrderId(null);
        order.setExpireTime(request.getExpireTime() == null ? now.plusMinutes(15) : request.getExpireTime());
        order.setCreateTime(now);
        order.setUpdateTime(now);

        return seckillOrderRepository.save(order);
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

    private void validateAndLockSeckillGoodsStock(SeckillGoods seckillGoods, Integer quantity) {
        if (seckillGoods.getStatus() == null || seckillGoods.getStatus() != 1) {
            throw new IllegalArgumentException("秒杀商品未上架或活动未开始");
        }
        if (seckillGoods.getAvailableStock() == null || seckillGoods.getAvailableStock() < quantity) {
            throw new IllegalArgumentException("秒杀库存不足");
        }

        int lockStock = seckillGoods.getLockStock() == null ? 0 : seckillGoods.getLockStock();
        seckillGoods.setAvailableStock(seckillGoods.getAvailableStock() - quantity);
        seckillGoods.setLockStock(lockStock + quantity);
    }

    private Integer defaultIfNull(Integer value, Integer defaultValue) {
        return value == null ? defaultValue : value;
    }

    private long generateId() {
        return snowflake.nextId();
    }

    private String generateOrderNo(LocalDateTime now) {
        long random = ThreadLocalRandom.current().nextLong(1000L, 9999L);
        return "SCK" + now.format(ORDER_NO_TIME_FORMATTER) + random;
    }
}

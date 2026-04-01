package com.raph.seckill.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.raph.seckill.entity.SeckillGoods;
import com.raph.seckill.repository.SeckillGoodsRepository;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;

@Service
public class SeckillGoodsService {

    private final SeckillGoodsRepository seckillGoodsRepository;
    private final Snowflake snowflake;

    public SeckillGoodsService(SeckillGoodsRepository seckillGoodsRepository,
                               @Value("${snowflake.worker-id:1}") long workerId,
                               @Value("${snowflake.datacenter-id:1}") long datacenterId) {
        this.seckillGoodsRepository = seckillGoodsRepository;
        this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
    }

    public List<SeckillGoods> queryGoods(Long activityId, Integer status) {
        if (activityId != null && status != null) {
            return seckillGoodsRepository.findByActivityIdAndStatusOrderByUpdateTimeDesc(activityId, status);
        }
        if (activityId != null) {
            return seckillGoodsRepository.findByActivityIdOrderByUpdateTimeDesc(activityId);
        }
        if (status != null) {
            return seckillGoodsRepository.findByStatusOrderByUpdateTimeDesc(status);
        }
        return seckillGoodsRepository.findAll();
    }

    public Optional<SeckillGoods> findById(Long id) {
        return seckillGoodsRepository.findById(id);
    }

    @Transactional
    public SeckillGoods create(SeckillGoods payload) {
        validateGoodsPayload(payload);

        LocalDateTime now = LocalDateTime.now();
        payload.setId(generateId());
        payload.setLockStock(defaultIfNull(payload.getLockStock(), 0));
        payload.setAvailableStock(resolveAvailableStock(payload));
        payload.setVersion(null);
        payload.setStatus(defaultIfNull(payload.getStatus(), 0));
        payload.setCreateTime(now);
        payload.setUpdateTime(now);

        validateStock(payload.getSeckillStock(), payload.getAvailableStock(), payload.getLockStock());
        return seckillGoodsRepository.save(payload);
    }

    @Transactional
    public SeckillGoods update(Long id, SeckillGoods payload) {
        validateGoodsPayload(payload);

        SeckillGoods existing = seckillGoodsRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("秒杀商品不存在"));

        existing.setActivityId(payload.getActivityId());
        existing.setGoodsId(payload.getGoodsId());
        existing.setSeckillPrice(payload.getSeckillPrice());
        existing.setSeckillStock(payload.getSeckillStock());
        existing.setLockStock(defaultIfNull(payload.getLockStock(), 0));
        existing.setAvailableStock(resolveAvailableStock(payload));
        existing.setPerUserLimit(payload.getPerUserLimit());
        existing.setStatus(payload.getStatus());
        existing.setUpdateTime(LocalDateTime.now());

        validateStock(existing.getSeckillStock(), existing.getAvailableStock(), existing.getLockStock());
        return seckillGoodsRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        if (!seckillGoodsRepository.existsById(id)) {
            throw new IllegalArgumentException("秒杀商品不存在");
        }
        seckillGoodsRepository.deleteById(id);
    }

    private void validateGoodsPayload(SeckillGoods payload) {
        if (payload == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (payload.getActivityId() == null) {
            throw new IllegalArgumentException("activityId 不能为空");
        }
        if (payload.getGoodsId() == null) {
            throw new IllegalArgumentException("goodsId 不能为空");
        }
        if (payload.getSeckillPrice() == null || payload.getSeckillPrice().signum() < 0) {
            throw new IllegalArgumentException("seckillPrice 非法");
        }
        if (payload.getSeckillStock() == null || payload.getSeckillStock() < 0) {
            throw new IllegalArgumentException("seckillStock 非法");
        }
        if (payload.getPerUserLimit() != null && payload.getPerUserLimit() <= 0) {
            throw new IllegalArgumentException("perUserLimit 必须大于 0");
        }
    }

    private Integer resolveAvailableStock(SeckillGoods payload) {
        Integer lockStock = defaultIfNull(payload.getLockStock(), 0);
        if (payload.getAvailableStock() != null) {
            return payload.getAvailableStock();
        }
        return payload.getSeckillStock() - lockStock;
    }

    private void validateStock(Integer seckillStock, Integer availableStock, Integer lockStock) {
        if (availableStock == null || availableStock < 0 || lockStock == null || lockStock < 0) {
            throw new IllegalArgumentException("库存字段非法");
        }
        if (seckillStock == null || seckillStock < 0) {
            throw new IllegalArgumentException("seckillStock 非法");
        }
        if (availableStock + lockStock > seckillStock) {
            throw new IllegalArgumentException("库存不平衡: availableStock + lockStock 不能大于 seckillStock");
        }
    }

    private Integer defaultIfNull(Integer value, Integer defaultValue) {
        return value == null ? defaultValue : value;
    }

    private long generateId() {
        return snowflake.nextId();
    }
}

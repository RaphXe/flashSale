package com.raph.seckill.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.raph.seckill.dto.StockLockAdjustRequest;
import com.raph.seckill.entity.SeckillActivity;
import com.raph.seckill.entity.SeckillGoods;
import com.raph.seckill.repository.SeckillActivityRepository;
import com.raph.seckill.repository.SeckillGoodsRepository;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;

@Service
public class SeckillActivityService {

    private final SeckillActivityRepository seckillActivityRepository;
    private final SeckillGoodsRepository seckillGoodsRepository;
    private final Snowflake snowflake;
    private final RestTemplate restTemplate;

    @Value("${services.stock.base-url:http://localhost:8083}")
    private String stockServiceBaseUrl;

    public SeckillActivityService(SeckillActivityRepository seckillActivityRepository,
                                  SeckillGoodsRepository seckillGoodsRepository,
                                  @Value("${snowflake.worker-id:1}") long workerId,
                                  @Value("${snowflake.datacenter-id:1}") long datacenterId) {
        this.seckillActivityRepository = seckillActivityRepository;
        this.seckillGoodsRepository = seckillGoodsRepository;
        this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
        this.restTemplate = new RestTemplate();
    }

    public List<SeckillActivity> queryActivities(Integer status, Boolean current) {
        if (Boolean.TRUE.equals(current)) {
            return seckillActivityRepository.findCurrentActivities(LocalDateTime.now());
        }
        if (status != null) {
            return seckillActivityRepository.findByStatusOrderByStartTimeAsc(status);
        }
        return seckillActivityRepository.findAllByOrderByStartTimeDesc();
    }

    public Optional<SeckillActivity> findById(Long id) {
        return seckillActivityRepository.findById(id);
    }

    @Transactional
    public SeckillActivity create(SeckillActivity payload) {
        validateActivity(payload);

        LocalDateTime now = LocalDateTime.now();
        Long activityId = generateId();

        Map<Long, Integer> stockLockMap = buildStockLockMap(payload.getSeckillGoods());
        adjustStockWithDelta(stockLockMap);

        payload.setId(activityId);
        payload.setStatus(defaultIfNull(payload.getStatus(), 0));
        payload.setCreateTime(now);
        payload.setUpdateTime(now);

        if (payload.getSeckillGoods() != null) {
            for (SeckillGoods goods : payload.getSeckillGoods()) {
                validateSeckillGoods(goods);
                if (goods.getId() == null) {
                    goods.setId(generateId());
                }
                goods.setActivityId(activityId);
                goods.setStatus(defaultIfNull(goods.getStatus(), payload.getStatus()));
                goods.setLockStock(defaultIfNull(goods.getLockStock(), 0));
                if (goods.getAvailableStock() == null) {
                    goods.setAvailableStock(goods.getSeckillStock());
                }
                // 不能将有version注解的字段设置为0，否则会导致数据库认为是更新而不是插入，抛出异常
                goods.setVersion(null);
                goods.setCreateTime(now);
                goods.setUpdateTime(now);
            }
        }

        return seckillActivityRepository.save(payload);
    }

    @Transactional
    public SeckillActivity update(Long id, SeckillActivity payload) {
        validateActivity(payload);

        SeckillActivity existing = seckillActivityRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("秒杀活动不存在"));

        boolean toOffline = payload.getStatus() != null
            && payload.getStatus() == 2
            && (existing.getStatus() == null || existing.getStatus() != 2);
        if (toOffline) {
            releaseUnsoldStockBackToStockService(id);
        }

        existing.setName(payload.getName());
        existing.setStartTime(payload.getStartTime());
        existing.setEndTime(payload.getEndTime());
        existing.setStatus(payload.getStatus());
        existing.setLimitPerPerson(payload.getLimitPerPerson());
        existing.setUpdateTime(LocalDateTime.now());

        return seckillActivityRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        if (!seckillActivityRepository.existsById(id)) {
            throw new IllegalArgumentException("秒杀活动不存在");
        }

        releaseUnsoldStockBackToStockService(id);
        seckillActivityRepository.deleteById(id);
    }

    @Transactional
    public void settleActivityStock(Long activityId) {
        if (activityId == null) {
            throw new IllegalArgumentException("activityId 不能为空");
        }
        releaseUnsoldStockBackToStockService(activityId);
    }

    private void validateActivity(SeckillActivity payload) {
        if (payload == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (payload.getName() == null || payload.getName().isBlank()) {
            throw new IllegalArgumentException("活动名称不能为空");
        }
        if (payload.getStartTime() == null || payload.getEndTime() == null) {
            throw new IllegalArgumentException("活动时间不能为空");
        }
        if (!payload.getEndTime().isAfter(payload.getStartTime())) {
            throw new IllegalArgumentException("活动结束时间必须晚于开始时间");
        }
        if (payload.getLimitPerPerson() != null && payload.getLimitPerPerson() <= 0) {
            throw new IllegalArgumentException("每人限购数量必须大于 0");
        }
    }

    private void validateSeckillGoods(SeckillGoods goods) {
        if (goods == null) {
            throw new IllegalArgumentException("秒杀商品配置不能为空");
        }
        if (goods.getGoodsId() == null) {
            throw new IllegalArgumentException("秒杀商品 goodsId 不能为空");
        }
        if (goods.getSeckillStock() == null || goods.getSeckillStock() <= 0) {
            throw new IllegalArgumentException("秒杀商品 seckillStock 必须大于 0");
        }
    }

    private Map<Long, Integer> buildStockLockMap(List<SeckillGoods> seckillGoods) {
        Map<Long, Integer> quantityDeltaByGoods = new HashMap<>();
        if (seckillGoods == null || seckillGoods.isEmpty()) {
            return quantityDeltaByGoods;
        }

        for (SeckillGoods goods : seckillGoods) {
            validateSeckillGoods(goods);
            quantityDeltaByGoods.merge(goods.getGoodsId(), goods.getSeckillStock(), Integer::sum);
        }
        return quantityDeltaByGoods;
    }

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

    // 下架或删除活动时，仅释放未出售库存（availableStock）回 stock-service。
    private void releaseUnsoldStockBackToStockService(Long activityId) {
        List<SeckillGoods> goodsList = seckillGoodsRepository.findByActivityIdForUpdate(activityId);
        if (goodsList == null || goodsList.isEmpty()) {
            return;
        }

        Map<Long, Integer> releaseDeltaByGoods = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();

        for (SeckillGoods goods : goodsList) {
            Integer unsold = goods.getAvailableStock();
            if (unsold == null || unsold <= 0) {
                continue;
            }

            // 计算要释放回 stock-service 的库存增量
            releaseDeltaByGoods.merge(goods.getGoodsId(), -unsold, Integer::sum);
            goods.setAvailableStock(0);
            Integer seckillStockValue = goods.getSeckillStock();
            int seckillStock = seckillStockValue == null ? 0 : seckillStockValue;
            goods.setSeckillStock(Math.max(0, seckillStock - unsold));
            goods.setUpdateTime(now);
        }

        adjustStockWithDelta(releaseDeltaByGoods);
        seckillGoodsRepository.saveAll(goodsList);
    }

    private Integer defaultIfNull(Integer value, Integer defaultValue) {
        return value == null ? defaultValue : value;
    }

    private long generateId() {
        return snowflake.nextId();
    }
}

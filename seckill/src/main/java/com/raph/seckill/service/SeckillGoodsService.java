package com.raph.seckill.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.raph.seckill.entity.SeckillGoods;
import com.raph.seckill.repository.SeckillGoodsRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import jakarta.annotation.PostConstruct;

@Service
public class SeckillGoodsService {

    private static final Logger log = LoggerFactory.getLogger(SeckillGoodsService.class);
    private static final String SECKILL_GOODS_DETAIL_CACHE_KEY_PREFIX = "seckill:goods:detail:";
    private static final String SECKILL_GOODS_ACTIVITY_GOODS_CACHE_KEY_PREFIX = "seckill:goods:activity-goods:";

    private final SeckillGoodsRepository seckillGoodsRepository;
    private final Snowflake snowflake;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;
    private final long detailCacheTtlSeconds;
    private final long bloomExpectedInsertions;
    private final double bloomFalsePositiveProbability;

    private BloomFilter<CharSequence> goodsIdBloomFilter;

    public SeckillGoodsService(SeckillGoodsRepository seckillGoodsRepository,
                               StringRedisTemplate stringRedisTemplate,
                               RedissonClient redisson,
                               ObjectMapper objectMapper,
                               @Value("${snowflake.worker-id:1}") long workerId,
                               @Value("${snowflake.datacenter-id:1}") long datacenterId,
                               @Value("${seckill.goods.cache.detail-ttl-seconds:300}") long detailCacheTtlSeconds,
                               @Value("${seckill.goods.cache.bloom.expected-insertions:100000}") long bloomExpectedInsertions,
                               @Value("${seckill.goods.cache.bloom.false-positive-probability:0.01}") double bloomFalsePositiveProbability) {
        this.seckillGoodsRepository = seckillGoodsRepository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisson = redisson;
        this.objectMapper = objectMapper;
        this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
        this.detailCacheTtlSeconds = detailCacheTtlSeconds;
        this.bloomExpectedInsertions = bloomExpectedInsertions;
        this.bloomFalsePositiveProbability = bloomFalsePositiveProbability;
    }

    // 预热布隆过滤器，防止缓存穿透攻击
    @PostConstruct
    public void initBloomFilter() {
        this.goodsIdBloomFilter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                bloomExpectedInsertions,
                bloomFalsePositiveProbability
        );
        List<Long> allIds = seckillGoodsRepository.findAllIds();
        for (Long id : allIds) {
            putIdIntoBloomFilter(id);
        }
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
        if (id == null) {
            return Optional.empty();
        }

        if (!mightContainByBloomFilter(id)) {
            return Optional.empty();
        }

        // 先走redis缓存
        String cacheKey = buildDetailCacheKey(id);
        SeckillGoods cached = readCache(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }

        // 分布式锁，限制单线程访问数据库
        String lockKey = "Lock:" + cacheKey;
        RLock lock = redisson.getLock(lockKey);
        try {
            boolean acquired = lock.tryLock();
            if (acquired) {
                // 二次验证缓存，防止击穿
                cached = readCache(cacheKey);
                if (cached != null) {
                    return Optional.of(cached);
                }

                Optional<SeckillGoods> dbGoods = seckillGoodsRepository.findById(id);
                dbGoods.ifPresent(this::writeGoodsCache);
                return dbGoods;
            }
            else {
                // 没抢到锁，过会可能已经有数据了
                Thread.sleep(50);
                return Optional.ofNullable(readCache(cacheKey));
            }
            
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public Optional<SeckillGoods> findByActivityIdAndGoodsId(Long activityId, Long goodsId) {
        if (activityId == null || goodsId == null) {
            return Optional.empty();
        }

        String cacheKey = buildActivityGoodsCacheKey(activityId, goodsId);
        SeckillGoods cached = readCache(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }

        String lockKey = "Lock:" + cacheKey;
        RLock lock = redisson.getLock(lockKey);
        try {
            boolean acquired = lock.tryLock();
            if (acquired) {
                cached = readCache(cacheKey);
                if (cached != null) {
                    return Optional.of(cached);
                }

                Optional<SeckillGoods> dbGoods = seckillGoodsRepository.findByActivityIdAndGoodsId(activityId, goodsId);
                dbGoods.ifPresent(this::writeGoodsCache);
                return dbGoods;
            }

            Thread.sleep(50);
            return Optional.ofNullable(readCache(cacheKey));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public Optional<SeckillGoods> findByActivityIdAndGoodsIdForUpdate(Long activityId, Long goodsId) {
        return seckillGoodsRepository.findByActivityIdAndGoodsIdForUpdate(activityId, goodsId);
    }

    public SeckillGoods saveAndRefreshCache(SeckillGoods goods) {
        SeckillGoods saved = seckillGoodsRepository.save(goods);
        writeGoodsCache(saved);
        putIdIntoBloomFilter(saved.getId());
        return saved;
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
        // 写入布隆过滤器和缓存
        return saveAndRefreshCache(payload);
    }

    @Transactional
    public SeckillGoods update(Long id, SeckillGoods payload) {
        validateGoodsPayload(payload);

        SeckillGoods existing = seckillGoodsRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("秒杀商品不存在"));

        Long oldActivityId = existing.getActivityId();
        Long oldGoodsId = existing.getGoodsId();

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
        SeckillGoods updated = saveAndRefreshCache(existing);

        if ((oldActivityId != null && !oldActivityId.equals(updated.getActivityId()))
                || (oldGoodsId != null && !oldGoodsId.equals(updated.getGoodsId()))) {
            deleteActivityGoodsCache(oldActivityId, oldGoodsId);
        }
        return updated;
    }

    @Transactional
    public void delete(Long id) {
        SeckillGoods existing = seckillGoodsRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("秒杀商品不存在"));

        seckillGoodsRepository.deleteById(id);
        deleteGoodsCache(existing.getId(), existing.getActivityId(), existing.getGoodsId());
    }

    private String buildDetailCacheKey(Long id) {
        return SECKILL_GOODS_DETAIL_CACHE_KEY_PREFIX + id;
    }

    private String buildActivityGoodsCacheKey(Long activityId, Long goodsId) {
        return SECKILL_GOODS_ACTIVITY_GOODS_CACHE_KEY_PREFIX + activityId + ":" + goodsId;
    }

    private boolean mightContainByBloomFilter(Long id) {
        if (id == null || goodsIdBloomFilter == null) {
            return false;
        }
        return goodsIdBloomFilter.mightContain(String.valueOf(id));
    }

    private void putIdIntoBloomFilter(Long id) {
        if (id == null || goodsIdBloomFilter == null) {
            return;
        }
        goodsIdBloomFilter.put(String.valueOf(id));
    }

    private void writeGoodsCache(SeckillGoods goods) {
        if (goods == null || goods.getId() == null) {
            return;
        }

        SeckillGoods cacheValue = toCacheValue(goods);
        writeCache(buildDetailCacheKey(goods.getId()), cacheValue);
        if (goods.getActivityId() != null && goods.getGoodsId() != null) {
            writeCache(buildActivityGoodsCacheKey(goods.getActivityId(), goods.getGoodsId()), cacheValue);
        }
    }

    private void writeCache(String key, SeckillGoods goods) {
        try {
            String payload = objectMapper.writeValueAsString(goods);
            stringRedisTemplate.opsForValue().set(key, payload, Duration.ofSeconds(detailCacheTtlSeconds + randomSeconds()));
        } catch (JsonProcessingException ex) {
            log.warn("写入秒杀商品缓存失败, key={}", key, ex);
        }
    }

    private SeckillGoods readCache(String key) {
        try {
            String payload = stringRedisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(payload)) {
                return null;
            }
            return objectMapper.readValue(payload, SeckillGoods.class);
        } catch (Exception ex) {
            log.warn("读取秒杀商品缓存失败, key={}", key, ex);
            stringRedisTemplate.delete(key);
            return null;
        }
    }

    private void deleteGoodsCache(Long id, Long activityId, Long goodsId) {
        if (id != null) {
            stringRedisTemplate.delete(buildDetailCacheKey(id));
        }
        deleteActivityGoodsCache(activityId, goodsId);
    }

    private void deleteActivityGoodsCache(Long activityId, Long goodsId) {
        if (activityId == null || goodsId == null) {
            return;
        }
        stringRedisTemplate.delete(buildActivityGoodsCacheKey(activityId, goodsId));
    }

    private SeckillGoods toCacheValue(SeckillGoods goods) {
        return new SeckillGoods(
                goods.getId(),
                null,
                goods.getActivityId(),
                goods.getGoodsId(),
                goods.getSeckillPrice(),
                goods.getSeckillStock(),
                goods.getAvailableStock(),
                goods.getLockStock(),
                goods.getPerUserLimit(),
                goods.getVersion(),
                goods.getStatus(),
                goods.getCreateTime(),
                goods.getUpdateTime()
        );
    }

    private long randomSeconds() {
        return (long) (Math.random() * 60);
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

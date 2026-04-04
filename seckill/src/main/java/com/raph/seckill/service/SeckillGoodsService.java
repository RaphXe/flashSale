package com.raph.seckill.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.raph.seckill.entity.SeckillGoods;
import com.raph.seckill.repository.SeckillGoodsRepository;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import jakarta.annotation.PostConstruct;

@Service
public class SeckillGoodsService {

    private static final Logger log = LoggerFactory.getLogger(SeckillGoodsService.class);
    private static final String SECKILL_GOODS_DETAIL_CACHE_KEY_PREFIX = "seckill:goods:detail:";
    private static final String SECKILL_GOODS_ACTIVITY_GOODS_CACHE_KEY_PREFIX = "seckill:goods:activity-goods:";
    private static final String SECKILL_GOODS_STOCK_CACHE_KEY_PREFIX = "seckill:goods:stock:";
    private static final String STOCK_FIELD_AVAILABLE = "availableStock";
    private static final String STOCK_FIELD_LOCK = "lockStock";
    private static final String STOCK_LUA_CACHE_NOT_READY = "-1";
    private static final String PRE_DEDUCT_STOCK_LUA = """
            local available = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '-1')
            local quantity = tonumber(ARGV[3] or '0')
            if available < 0 then
                return -1
            end
            if quantity <= 0 then
                return -3
            end
            if available < quantity then
                return 0
            end
            redis.call('HINCRBY', KEYS[1], ARGV[1], -quantity)
            redis.call('HINCRBY', KEYS[1], ARGV[2], quantity)
            return 1
            """;
    private static final String RELEASE_STOCK_LUA = """
            local lockStock = tonumber(redis.call('HGET', KEYS[1], ARGV[2]) or '-1')
            local quantity = tonumber(ARGV[3] or '0')
            if lockStock < 0 then
                return -1
            end
            if quantity <= 0 then
                return -3
            end
            if lockStock < quantity then
                return 0
            end
            redis.call('HINCRBY', KEYS[1], ARGV[2], -quantity)
            redis.call('HINCRBY', KEYS[1], ARGV[1], quantity)
            return 1
            """;

    private final SeckillGoodsRepository seckillGoodsRepository;
    private final Snowflake snowflake;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;
    private final long detailCacheTtlSeconds;
    private final long bloomExpectedInsertions;
    private final double bloomFalsePositiveProbability;
    private final RedisScript<Long> preDeductStockScript;
    private final RedisScript<Long> releaseStockScript;

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
        this.preDeductStockScript = buildLongScript(PRE_DEDUCT_STOCK_LUA);
        this.releaseStockScript = buildLongScript(RELEASE_STOCK_LUA);
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
        writeStockCache(saved);
        putIdIntoBloomFilter(saved.getId());
        return saved;
    }

    public void warmupStockCache(SeckillGoods goods) {
        writeStockCache(goods);
    }

    // 锁定订单库存，通过redis预扣减保证高并发下的库存一致性，db做最终扣减和校验，失败则回滚redis预扣减结果。
    @Transactional
    public SeckillGoods lockStockForOrder(Long activityId, Long goodsId, Integer quantity) {
        if (activityId == null || goodsId == null) {
            throw new IllegalArgumentException("activityId 或 goodsId 不能为空");
        }
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("quantity 必须大于 0");
        }

        // 乐观锁
        SeckillGoods seckillGoods = seckillGoodsRepository
                .findByActivityIdAndGoodsId(activityId, goodsId)
                .orElseThrow(() -> new IllegalArgumentException("秒杀商品不存在"));

        if (seckillGoods.getStatus() == null || seckillGoods.getStatus() != 1) {
            throw new IllegalArgumentException("秒杀商品未上架或活动未开始");
        }

        // 使用redis脚本进行原子化预扣减
        preDeductStockWithRedisScript(activityId, goodsId, quantity);
        try {
            applyPreDeductToDb(seckillGoods, quantity);
            seckillGoods.setUpdateTime(LocalDateTime.now());
            return saveAndRefreshCache(seckillGoods);
        } catch (RuntimeException ex) {
            try {
                // 数据库扣减失败，回滚redis库存
                releaseLockedStockWithRedisScript(activityId, goodsId, quantity);
            } catch (RuntimeException rollbackEx) {
                ex.addSuppressed(rollbackEx);
            }
            throw ex;
        }
    }

    @Transactional
    public SeckillGoods releaseLockedStockForOrder(Long activityId, Long goodsId, Integer quantity) {
        if (activityId == null || goodsId == null) {
            throw new IllegalArgumentException("activityId 或 goodsId 不能为空");
        }
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("释放库存数量非法");
        }

        SeckillGoods seckillGoods = seckillGoodsRepository
                .findByActivityIdAndGoodsIdForUpdate(activityId, goodsId)
                .orElseThrow(() -> new IllegalArgumentException("秒杀商品不存在"));

        releaseLockedStockWithRedisScript(activityId, goodsId, quantity);
        try {
            applyReleaseToDb(seckillGoods, quantity);
            seckillGoods.setUpdateTime(LocalDateTime.now());
            return saveAndRefreshCache(seckillGoods);
        } catch (RuntimeException ex) {
            try {
                preDeductStockWithRedisScript(activityId, goodsId, quantity);
            } catch (RuntimeException rollbackEx) {
                ex.addSuppressed(rollbackEx);
            }
            throw ex;
        }
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

    private String buildStockCacheKey(Long activityId, Long goodsId) {
        return SECKILL_GOODS_STOCK_CACHE_KEY_PREFIX + activityId + ":" + goodsId;
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

    private void writeStockCache(SeckillGoods goods) {
        if (goods == null || goods.getActivityId() == null || goods.getGoodsId() == null) {
            return;
        }

        int available = defaultIfNull(goods.getAvailableStock(), 0);
        int lockStock = defaultIfNull(goods.getLockStock(), 0);
        String stockKey = buildStockCacheKey(goods.getActivityId(), goods.getGoodsId());

        Map<String, String> stockPayload = new HashMap<>();
        stockPayload.put(STOCK_FIELD_AVAILABLE, String.valueOf(available));
        stockPayload.put(STOCK_FIELD_LOCK, String.valueOf(lockStock));

        stringRedisTemplate.opsForHash().putAll(stockKey, stockPayload);
        stringRedisTemplate.expire(stockKey, Duration.ofSeconds(detailCacheTtlSeconds + randomSeconds()));
    }

    private void preDeductStockWithRedisScript(Long activityId, Long goodsId, Integer quantity) {
        Long result = executeStockScriptWithRetry(preDeductStockScript, activityId, goodsId, quantity);
        if (result == null) {
            throw new IllegalStateException("Redis 库存预扣减执行失败");
        }
        if (result == 1L) {
            return;
        }
        if (result == 0L) {
            throw new IllegalArgumentException("秒杀库存不足");
        }
        if (result == -3L) {
            throw new IllegalArgumentException("quantity 必须大于 0");
        }
        throw new IllegalStateException("Redis 库存预扣减返回未知状态: " + result);
    }

    private void releaseLockedStockWithRedisScript(Long activityId, Long goodsId, Integer quantity) {
        Long result = executeStockScriptWithRetry(releaseStockScript, activityId, goodsId, quantity);
        if (result == null) {
            throw new IllegalStateException("Redis 库存释放执行失败");
        }
        if (result == 1L) {
            return;
        }
        if (result == 0L) {
            throw new IllegalArgumentException("锁定库存不足，无法释放");
        }
        if (result == -3L) {
            throw new IllegalArgumentException("释放库存数量非法");
        }
        throw new IllegalStateException("Redis 库存释放返回未知状态: " + result);
    }

    private Long executeStockScriptWithRetry(RedisScript<Long> script, Long activityId, Long goodsId, Integer quantity) {
        String stockKey = buildStockCacheKey(activityId, goodsId);
        Long first = stringRedisTemplate.execute(
                script,
                Collections.singletonList(stockKey),
                STOCK_FIELD_AVAILABLE,
                STOCK_FIELD_LOCK,
                String.valueOf(quantity)
        );

        if (first != null && !STOCK_LUA_CACHE_NOT_READY.equals(String.valueOf(first))) {
            return first;
        }

        SeckillGoods dbGoods = seckillGoodsRepository.findByActivityIdAndGoodsIdForUpdate(activityId, goodsId)
                .orElseThrow(() -> new IllegalArgumentException("秒杀商品不存在"));
        writeStockCache(dbGoods);

        return stringRedisTemplate.execute(
                script,
                Collections.singletonList(stockKey),
                STOCK_FIELD_AVAILABLE,
                STOCK_FIELD_LOCK,
                String.valueOf(quantity)
        );
    }

    private void applyPreDeductToDb(SeckillGoods seckillGoods, Integer quantity) {
        int available = defaultIfNull(seckillGoods.getAvailableStock(), 0);
        int lockStock = defaultIfNull(seckillGoods.getLockStock(), 0);
        if (available < quantity) {
            throw new IllegalStateException("数据库可用库存不足，无法完成预扣减");
        }
        seckillGoods.setAvailableStock(available - quantity);
        seckillGoods.setLockStock(lockStock + quantity);
    }

    private void applyReleaseToDb(SeckillGoods seckillGoods, Integer quantity) {
        int available = defaultIfNull(seckillGoods.getAvailableStock(), 0);
        int lockStock = defaultIfNull(seckillGoods.getLockStock(), 0);
        if (lockStock < quantity) {
            throw new IllegalStateException("数据库锁定库存不足，无法释放");
        }
        seckillGoods.setLockStock(lockStock - quantity);
        seckillGoods.setAvailableStock(available + quantity);
    }

    private RedisScript<Long> buildLongScript(String scriptText) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(scriptText);
        script.setResultType(Long.class);
        return script;
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
        } catch (IOException | RuntimeException ex) {
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

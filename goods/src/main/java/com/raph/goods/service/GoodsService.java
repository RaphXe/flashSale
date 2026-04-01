package com.raph.goods.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.raph.goods.entity.Goods;
import com.raph.goods.repository.GoodsRepository;

import jakarta.annotation.PostConstruct;

@Service
public class GoodsService {

    private static final String GOODS_DETAIL_CACHE_KEY_PREFIX = "goods:detail:";

    private final GoodsRepository goodsRepository;
    private final RedisTemplate<String, Goods> goodsRedisTemplate;
    private final RedissonClient redisson;
    private final long detailCacheTtlSeconds;
    private final long bloomExpectedInsertions;
    private final double bloomFalsePositiveProbability;

    private BloomFilter<CharSequence> goodsIdBloomFilter;

    public GoodsService(
            GoodsRepository goodsRepository,
            RedisTemplate<String, Goods> goodsRedisTemplate,
            RedissonClient redisson,
            @Value("${goods.cache.detail-ttl-seconds:300}") long detailCacheTtlSeconds,
            @Value("${goods.cache.bloom.expected-insertions:100000}") long bloomExpectedInsertions,
            @Value("${goods.cache.bloom.false-positive-probability:0.01}") double bloomFalsePositiveProbability
    ) {
        this.goodsRepository = goodsRepository;
        this.goodsRedisTemplate = goodsRedisTemplate;
        this.redisson = redisson;
        this.detailCacheTtlSeconds = detailCacheTtlSeconds;
        this.bloomExpectedInsertions = bloomExpectedInsertions;
        this.bloomFalsePositiveProbability = bloomFalsePositiveProbability;
    }

    // 预热布隆过滤器，加载所有商品ID
    @PostConstruct
    public void initBloomFilter() {
        this.goodsIdBloomFilter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                bloomExpectedInsertions,
                bloomFalsePositiveProbability
        );
        List<Long> allIds = goodsRepository.findAllIds();
        for (Long id : allIds) {
            goodsIdBloomFilter.put(String.valueOf(id));
        }
    }

    public List<Goods> queryGoods(Integer status, String keyword, BigDecimal minPrice, BigDecimal maxPrice) {
        boolean hasKeyword = StringUtils.hasText(keyword);

        if (status != null && hasKeyword) {
            return goodsRepository.searchByStatusAndKeyword(status, keyword.trim());
        }
        if (status != null) {
            return goodsRepository.findAllByStatusOrderByUpdateTimeDesc(status);
        }
        if (minPrice != null && maxPrice != null) {
            return goodsRepository.findByPriceRange(minPrice, maxPrice);
        }
        if (hasKeyword) {
            return goodsRepository.findByNameContaining(keyword.trim());
        }
        return goodsRepository.findAll();
    }

    public Optional<Goods> findById(Long id) {
        // 布隆过滤器，防御缓存穿透
        if (!mightContainByBloomFilter(id)) {
            return Optional.empty();
        }

        // 先查缓存
        String cacheKey = buildDetailCacheKey(id);
        Goods cachedGoods = readDetailCacheSafely(cacheKey);
        if (cachedGoods != null) {
            return Optional.of(cachedGoods);
        }

        String lockKey = "Lock:" + cacheKey;
        // 获取分布式锁，防止缓存击穿
        RLock lock = redisson.getLock(lockKey);
        try {
            boolean acquired = lock.tryLock();
            if (acquired) {
                // 二次验证缓存，防止击穿
                cachedGoods = readDetailCacheSafely(cacheKey);
                if (cachedGoods != null) {
                    return Optional.of(cachedGoods);
                }
                // 从数据库查询
                Optional<Goods> dbGoods = goodsRepository.findById(id);
                dbGoods.ifPresent(goods -> goodsRedisTemplate.opsForValue().set(
                        cacheKey,
                        goods,
                        Duration.ofSeconds(detailCacheTtlSeconds)
                ));
                return dbGoods;
            } else {
                // 获取锁失败，可能是热点数据被大量访问，短暂等待后重试
                Thread.sleep(50);
                // 可能已经有其他线程加载了缓存，直接读取缓存
                return Optional.ofNullable(readDetailCacheSafely(cacheKey));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    

    public List<Goods> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return goodsRepository.findAllById(ids);
    }

    @Transactional
    public Goods create(Goods goods) {
        LocalDateTime now = LocalDateTime.now();
        goods.setId(null);
        goods.setCreateTime(now);
        goods.setUpdateTime(now);
        Goods created = goodsRepository.save(goods);
        // 写入布隆过滤器和缓存
        putIdIntoBloomFilter(created.getId());
        writeDetailCache(created);
        return created;
    }

    @Transactional
    public Goods update(Long id, Goods payload) {
        Goods existing = goodsRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在"));

        existing.setName(payload.getName());
        existing.setDescription(payload.getDescription());
        existing.setPrice(payload.getPrice());
        existing.setStatus(payload.getStatus());
        existing.setUpdateTime(LocalDateTime.now());

        Goods updated = goodsRepository.save(existing);
        // 写入缓存
        writeDetailCache(updated);
        // 写入布隆过滤器
        putIdIntoBloomFilter(updated.getId());
        return updated;
    }

    @Transactional
    public void delete(Long id) {
        if (!goodsRepository.existsById(id)) {
            throw new IllegalArgumentException("商品不存在");
        }
        goodsRepository.deleteById(id);
        deleteDetailCache(id);
    }

    private String buildDetailCacheKey(Long id) {
        return GOODS_DETAIL_CACHE_KEY_PREFIX + id;
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

    private void writeDetailCache(Goods goods) {
        if (goods.getId() == null) {
            return;
        }
        // 引入随机过期时间，防止缓存雪崩
        long randomSeconds = (long) (Math.random() * 60);
        goodsRedisTemplate.opsForValue().set(
                buildDetailCacheKey(goods.getId()),
                goods,
                Duration.ofSeconds(detailCacheTtlSeconds + randomSeconds)
        );
    }

    private void deleteDetailCache(Long id) {
        goodsRedisTemplate.delete(buildDetailCacheKey(id));
    }

    private Goods readDetailCacheSafely(String cacheKey) {
        try {
            return goodsRedisTemplate.opsForValue().get(cacheKey);
        } catch (ClassCastException ex) {
            // 兼容历史缓存中非 Goods 类型数据，删除后回源数据库重建缓存
            goodsRedisTemplate.delete(cacheKey);
            return null;
        }
    }
}

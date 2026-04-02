package com.raph.goods.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.raph.goods.entity.Goods;
import com.raph.goods.entity.GoodsDocument;
import com.raph.goods.repository.GoodsDocumentRepository;
import com.raph.goods.repository.GoodsRepository;

import jakarta.annotation.PostConstruct;

@Service
public class GoodsService {

    private static final Logger log = LoggerFactory.getLogger(GoodsService.class);
    private static final String GOODS_DETAIL_CACHE_KEY_PREFIX = "goods:detail:";
    private static final String GOODS_SEARCH_CACHE_KEY_PREFIX = "goods:search:keyword:";

    private final GoodsRepository goodsRepository;
    private final GoodsDocumentRepository goodsDocumentRepository;
    private final ElasticsearchOperations elasticsearchOperations;
    private final RedisTemplate<String, Goods> goodsRedisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redisson;
    private final long detailCacheTtlSeconds;
    private final long searchCacheTtlSeconds;
    private final long bloomExpectedInsertions;
    private final double bloomFalsePositiveProbability;

    private BloomFilter<CharSequence> goodsIdBloomFilter;

    public GoodsService(
            GoodsRepository goodsRepository,
            GoodsDocumentRepository goodsDocumentRepository,
            ElasticsearchOperations elasticsearchOperations,
            RedisTemplate<String, Goods> goodsRedisTemplate,
            StringRedisTemplate stringRedisTemplate,
            RedissonClient redisson,
            @Value("${goods.cache.detail-ttl-seconds:300}") long detailCacheTtlSeconds,
            @Value("${goods.cache.search-ttl-seconds:300}") long searchCacheTtlSeconds,
            @Value("${goods.cache.bloom.expected-insertions:100000}") long bloomExpectedInsertions,
            @Value("${goods.cache.bloom.false-positive-probability:0.01}") double bloomFalsePositiveProbability
    ) {
        this.goodsRepository = goodsRepository;
        this.goodsDocumentRepository = goodsDocumentRepository;
        this.elasticsearchOperations = elasticsearchOperations;
        this.goodsRedisTemplate = goodsRedisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisson = redisson;
        this.detailCacheTtlSeconds = detailCacheTtlSeconds;
        this.searchCacheTtlSeconds = searchCacheTtlSeconds;
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

    // 使用关键词搜索商品，优先ES，失败后降级到Redis，再降级到MySQL
    public List<Goods> searchByKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }
        String normalizedKeyword = keyword.trim();
        String searchCacheKey = buildSearchCacheKey(normalizedKeyword);

        try {
            String wildcardKeyword = normalizedKeyword.replace("*", "").replace("?", "");
            NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b
                    .should(s -> s.match(m -> m.field("name").query(normalizedKeyword)))
                    .should(s -> s.match(m -> m.field("description").query(normalizedKeyword)))
                    .should(s -> s.wildcard(w -> w.field("name").value("*" + wildcardKeyword + "*")))
                    .should(s -> s.wildcard(w -> w.field("description").value("*" + wildcardKeyword + "*")))
                    .minimumShouldMatch("1")))
                .build();

            SearchHits<GoodsDocument> hits = elasticsearchOperations.search(query, GoodsDocument.class);
            List<Goods> result = hits.stream()
                .map(hit -> toGoods(hit.getContent()))
                .collect(Collectors.toList());
                
            // 将ES搜索结果写入Redis缓存，供降级使用    
            writeSearchCache(searchCacheKey, result);
            // 记录搜索来源和结果数量，便于后续分析关键词搜索的热点和效果
            putKeywordSearchMdc("es", normalizedKeyword, result);
            return result;
        } catch (Exception ex) {
            log.warn("ES搜索失败，降级到Redis/MySQL，keyword={}", normalizedKeyword, ex);

            List<Goods> redisResult = readSearchCache(searchCacheKey);
            if (!redisResult.isEmpty()) {
                putKeywordSearchMdc("redis", normalizedKeyword, redisResult);
                return redisResult;
            }

            // redis缓存未命中，降级到MySQL查询
            List<Goods> mysqlResult = goodsRepository.findByNameContainingOrDescriptionContaining(
                    normalizedKeyword,
                    normalizedKeyword
            );
            // 将MySQL查询结果写入Redis缓存，供下次搜索使用
            writeSearchCache(searchCacheKey, mysqlResult);
            // 记录搜索来源和结果数量，便于后续分析关键词搜索的热点和效果
            putKeywordSearchMdc("mysql", normalizedKeyword, mysqlResult);
            return mysqlResult;
        }
    }

    // 全量同步MySQL数据到Elasticsearch
    public int syncAllFromMysqlToElasticsearch() {
        List<Goods> goodsList = goodsRepository.findAll();
        if (goodsList.isEmpty()) {
            return 0;
        }
        List<GoodsDocument> documents = goodsList.stream()
                .map(this::toDocument)
                .collect(Collectors.toList());
        goodsDocumentRepository.saveAll(documents);
        return documents.size();
    }

    public long countAllGoods() {
        return goodsRepository.count();
    }

    public List<Long> findAllGoodsIds() {
        return goodsRepository.findAllIds();
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

        // 对ID进行去重
        List<Long> orderedIds = new ArrayList<>();
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            orderedIds.add(id);
            uniqueIds.add(id);
        }
        if (orderedIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量构建缓存key
        List<String> cacheKeys = uniqueIds.stream()
                .map(this::buildDetailCacheKey)
                .collect(Collectors.toList());

        Map<Long, Goods> goodsById = new HashMap<>();
        List<Long> missingIds = new ArrayList<>();

        try {
            List<Goods> cachedGoodsList = goodsRedisTemplate.opsForValue().multiGet(cacheKeys);
            int index = 0;
            // 根据缓存key的顺序，将查询结果与ID对应起来，构建ID到Goods的映射，同时记录未命中缓存的ID
            for (Long id : uniqueIds) {
                Goods cachedGoods = null;
                if (cachedGoodsList != null && index < cachedGoodsList.size()) {
                    cachedGoods = cachedGoodsList.get(index);
                }
                if (cachedGoods != null) {
                    goodsById.put(id, cachedGoods);
                } else {
                    missingIds.add(id);
                }
                index++;
            }
        } catch (Exception ex) {
            log.warn("批量读取商品缓存失败，回源数据库，idsCount={}", uniqueIds.size(), ex);
            missingIds.addAll(uniqueIds);
        }

        if (!missingIds.isEmpty()) {
            String lockKey = "Lock:Batch:" + missingIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            // 获取分布式锁，防止缓存击穿
            RLock lock = redisson.getLock(lockKey);
            try {
                boolean acquired = lock.tryLock();
                if (acquired) {
                    List<Goods> dbGoodsList = goodsRepository.findAllById(missingIds);
                    for (Goods goods : dbGoodsList) {
                        goodsById.put(goods.getId(), goods);
                        writeDetailCache(goods);
                        putIdIntoBloomFilter(goods.getId());
                    }
                }
                else {
                    // 别的线程可能已经加载了缓存，短暂等待后重试
                    Thread.sleep(50);
                    for (Long id : missingIds) {
                        Goods cachedGoods = readDetailCacheSafely(buildDetailCacheKey(id));
                        if (cachedGoods != null) {
                            goodsById.put(id, cachedGoods);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

        // 根据输入ID的顺序，构建结果列表，保证返回结果的顺序与输入ID一致
        List<Goods> result = new ArrayList<>();
        for (Long id : orderedIds) {
            Goods goods = goodsById.get(id);
            if (goods != null) {
                result.add(goods);
            }
        }
        return result;
    }

    // 预热缓存
    public int warmupDetailCache(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }

        Set<Long> uniqueIds = new LinkedHashSet<>(ids);
        List<Goods> goodsList = goodsRepository.findAllById(uniqueIds);
        int warmedCount = 0;
        for (Goods goods : goodsList) {
            writeDetailCache(goods);
            // 预热布隆过滤器
            putIdIntoBloomFilter(goods.getId());
            warmedCount++;
        }
        return warmedCount;
    }

    @Transactional
    public Goods create(Goods goods) {
        LocalDateTime now = LocalDateTime.now();
        goods.setId(null);
        goods.setCreateTime(now);
        goods.setUpdateTime(now);
        Goods created = goodsRepository.save(goods);
        // 写入布隆过滤器
        putIdIntoBloomFilter(created.getId());
        // 写入缓存
        writeDetailCache(created);
        // 同步到Elasticsearch
        syncToElasticsearch(created);
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
        // 同步到Elasticsearch
        syncToElasticsearch(updated);
        return updated;
    }

    @Transactional
    public void delete(Long id) {
        if (!goodsRepository.existsById(id)) {
            throw new IllegalArgumentException("商品不存在");
        }
        goodsRepository.deleteById(id);
        deleteDetailCache(id);
        deleteFromElasticsearch(id);
    }

    private void syncToElasticsearch(Goods goods) {
        try {
            goodsDocumentRepository.save(toDocument(goods));
        } catch (Exception ex) {
            log.error("同步商品到ES失败, goodsId={}", goods.getId(), ex);
        }
    }

    private void deleteFromElasticsearch(Long id) {
        try {
            goodsDocumentRepository.deleteById(id);
        } catch (Exception ex) {
            log.error("从ES删除商品失败, goodsId={}", id, ex);
        }
    }

    private GoodsDocument toDocument(Goods goods) {
        return GoodsDocument.builder()
                .id(goods.getId())
                .name(goods.getName())
                .description(goods.getDescription())
                .price(goods.getPrice())
                .status(goods.getStatus())
                .updateTime(goods.getUpdateTime())
                .build();
    }

    private Goods toGoods(GoodsDocument document) {
        return Goods.builder()
                .id(document.getId())
                .name(document.getName())
                .description(document.getDescription())
                .price(document.getPrice())
                .status(document.getStatus())
                .updateTime(document.getUpdateTime())
                .build();
    }

    private String buildDetailCacheKey(Long id) {
        return GOODS_DETAIL_CACHE_KEY_PREFIX + id;
    }

    private String buildSearchCacheKey(String keyword) {
        return GOODS_SEARCH_CACHE_KEY_PREFIX + keyword.toLowerCase();
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
    
    private void writeSearchCache(String cacheKey, List<Goods> goodsList) {
        if (goodsList == null || goodsList.isEmpty()) {
            return;
        }
        // 缓存ID列表，以逗号分割
        String idsPayload = goodsList.stream()
                .map(Goods::getId)
                .filter(id -> id != null)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        if (!StringUtils.hasText(idsPayload)) {
            return;
        }
        // 引入随机过期时间，防止缓存雪崩
        stringRedisTemplate.opsForValue().set(
                cacheKey,
                idsPayload,
                Duration.ofSeconds(searchCacheTtlSeconds + (long)(Math.random() * 60))
        );
    }
    
    private List<Goods> readSearchCache(String cacheKey) {
        try {
            String idsPayload = stringRedisTemplate.opsForValue().get(cacheKey);
            if (!StringUtils.hasText(idsPayload)) {
                return Collections.emptyList();
            }

            // 读取并解析以逗号分割的ID列表
            List<Long> ids = Arrays.stream(idsPayload.split(","))
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .map(Long::valueOf)
                    .collect(Collectors.toList());
            if (ids.isEmpty()) {
                return Collections.emptyList();
            }

            List<Goods> goodsList = new ArrayList<>(findByIds(ids));
            if (goodsList.isEmpty()) {
                return Collections.emptyList();
            }

            Map<Long, Integer> idOrder = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                idOrder.put(ids.get(i), i);
            }
            goodsList.sort(Comparator.comparingInt(g -> idOrder.getOrDefault(g.getId(), Integer.MAX_VALUE)));
            return goodsList;
        } catch (Exception ex) {
            log.warn("读取搜索结果Redis缓存失败，cacheKey={}", cacheKey, ex);
            stringRedisTemplate.delete(cacheKey);
            return Collections.emptyList();
        }
    }

    // 将关键词搜索也记录到MDC中，方便开展热点分析
    private void putKeywordSearchMdc(String source, String keyword, List<Goods> goodsList) {
        MDC.put("event", "goods_keyword_search");
        MDC.put("searchSource", source);
        MDC.put("searchKeyword", keyword);
        int resultCount = goodsList == null ? 0 : goodsList.size();
        MDC.put("resultCount", String.valueOf(resultCount));

        if (goodsList == null || goodsList.isEmpty()) {
            MDC.put("goodsIds", "");
            return;
        }

        String goodsIds = goodsList.stream()
                .map(Goods::getId)
                .filter(id -> id != null)
                .limit(20)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        MDC.put("goodsIds", goodsIds);
    }
}

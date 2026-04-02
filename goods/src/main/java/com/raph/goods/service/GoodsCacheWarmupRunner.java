package com.raph.goods.service;

import java.util.ArrayList;
import java.util.List;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.util.NamedValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GoodsCacheWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GoodsCacheWarmupRunner.class);

    private final GoodsService goodsService;
    private final ElasticsearchClient elasticsearchClient;
    private final boolean warmupEnabled;
    private final String esIndexPattern;
    private final int warmupMaxCount;
    private final double warmupRatio;

    public GoodsCacheWarmupRunner(
            GoodsService goodsService,
            ElasticsearchClient elasticsearchClient,
            @Value("${goods.cache.warmup.enabled:true}") boolean warmupEnabled,
            @Value("${goods.cache.warmup.es-index-pattern:goods-service-*}") String esIndexPattern,
            @Value("${goods.cache.warmup.max-count:1000}") int warmupMaxCount,
            @Value("${goods.cache.warmup.ratio:0.2}") double warmupRatio
    ) {
        this.goodsService = goodsService;
        this.elasticsearchClient = elasticsearchClient;
        this.warmupEnabled = warmupEnabled;
        this.esIndexPattern = esIndexPattern;
        this.warmupMaxCount = warmupMaxCount;
        this.warmupRatio = warmupRatio;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!warmupEnabled) {
            log.info("商品缓存预热已关闭");
            return;
        }

        try {
            List<Long> hotGoodsIds = loadHotGoodsIdsFromEs();
            if (hotGoodsIds.isEmpty()) {
                log.info("未从ES获取到可预热的商品ID");
                return;
            }
            int warmedCount = goodsService.warmupDetailCache(hotGoodsIds);
            log.info("商品缓存预热完成，候选ID数量={}，实际预热数量={}", hotGoodsIds.size(), warmedCount);
        } catch (Exception ex) {
            log.warn("商品缓存预热失败，跳过本次预热: {}", ex.getMessage());
        }
    }

    // 使用ES加载热点商品ID
    private List<Long> loadHotGoodsIdsFromEs() throws Exception {
        SearchRequest request = buildSearchRequest();
        SearchResponse<Void> response = elasticsearchClient.search(request, Void.class);

        long uniqueGoodsCount = extractUniqueGoodsCount(response);
        List<Long> orderedIds = extractOrderedGoodsIds(response);
        if (orderedIds.isEmpty()) {
            return orderedIds;
        }

        int warmupTarget = calculateWarmupTarget(uniqueGoodsCount, orderedIds.size());
        return orderedIds.subList(0, warmupTarget);
    }

    private SearchRequest buildSearchRequest() {
        int aggregationSize = Math.max(1, warmupMaxCount);
        return SearchRequest.of(s -> s
                .index(esIndexPattern)
                .size(0)
                .query(q -> q.term(t -> t.field("event.keyword").value("goods_detail_access")))
                .aggregations("unique_goods_count", a -> a.cardinality(c -> c.field("goodsId.keyword")))
                .aggregations("goods_ids", a -> a.terms(t -> t
                        .field("goodsId.keyword")
                        .size(aggregationSize)
                    .order(NamedValue.of("_count", SortOrder.Desc))))
        );
    }

    private long extractUniqueGoodsCount(SearchResponse<Void> response) {
        Aggregate uniqueAgg = response.aggregations().get("unique_goods_count");
        if (uniqueAgg == null || !uniqueAgg.isCardinality()) {
            return 0L;
        }
        return uniqueAgg.cardinality().value();
    }

    private List<Long> extractOrderedGoodsIds(SearchResponse<Void> response) {
        List<Long> ids = new ArrayList<>();
        Aggregate termsAgg = response.aggregations().get("goods_ids");
        if (termsAgg == null || !termsAgg.isSterms()) {
            return ids;
        }

        List<StringTermsBucket> buckets = termsAgg.sterms().buckets().array();
        for (StringTermsBucket bucket : buckets) {
            String key = bucket.key().stringValue();
            if (!StringUtils.hasText(key)) {
                continue;
            }
            try {
                ids.add(Long.parseLong(key));
            } catch (NumberFormatException ignored) {
                // 忽略无法转换为Long的key，避免预热中断
            }
        }
        return ids;
    }

    private int calculateWarmupTarget(long uniqueGoodsCount, int availableIdsSize) {
        int cappedMaxCount = Math.max(1, warmupMaxCount);
        long nonNegativeUniqueCount = Math.max(0L, uniqueGoodsCount);
        double effectiveRatio = warmupRatio <= 0 ? 0.2 : warmupRatio;

        int ratioCount = (int) Math.ceil(nonNegativeUniqueCount * effectiveRatio);
        ratioCount = Math.max(1, ratioCount);

        int target = Math.min(ratioCount, cappedMaxCount);
        return Math.min(target, availableIdsSize);
    }
}

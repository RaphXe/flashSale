package com.raph.goods.controller;

import com.raph.goods.entity.Goods;
import com.raph.goods.service.GoodsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/goods")
public class GoodsController {

    private static final Logger log = LoggerFactory.getLogger(GoodsController.class);

    private final GoodsService goodsService;

    public GoodsController(GoodsService goodsService) {
        this.goodsService = goodsService;
    }

    @GetMapping
    public ResponseEntity<List<Goods>> list(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice
    ) {
        return ResponseEntity.ok(goodsService.queryGoods(status, keyword, minPrice, maxPrice));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable Long id) {
        // MDC记录数据访问详情，便于后续开展热点数据分析
        MDC.put("event", "goods_detail_access");
        MDC.put("goodsId", String.valueOf(id));
        try {
            Optional<Goods> goodsOptional = goodsService.findById(id);
            if (goodsOptional.isEmpty()) {
                MDC.put("hit", "false");
                log.info("获取商品详情，商品不存在");
                Map<String, String> response = new HashMap<>();
                response.put("message", "商品不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            MDC.put("hit", "true");
            log.info("获取商品详情成功");
            return ResponseEntity.ok(goodsOptional.get());
        } finally {
            MDC.clear();
        }
    }

    @PostMapping("/batch")
    public ResponseEntity<List<Goods>> batchDetail(@RequestBody List<Long> ids) {
        
        MDC.put("event", "goods_batch_detail_access");
        MDC.put("idsCount", String.valueOf(ids == null ? 0 : ids.size()));
        try {
            List<Goods> result = goodsService.findByIds(ids);
            MDC.put("resultCount", String.valueOf(result.size()));
            log.info("批量获取商品详情成功");
            return ResponseEntity.ok(result);
        } finally {
            MDC.clear();
        }
    }

    @PostMapping
    public ResponseEntity<Goods> create(@RequestBody Goods goods) {
        Goods created = goodsService.create(goods);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Goods goods) {
        try {
            return ResponseEntity.ok(goodsService.update(id, goods));
        } catch (IllegalArgumentException ex) {
            Map<String, String> response = new HashMap<>();
            response.put("message", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            goodsService.delete(id);
            Map<String, String> response = new HashMap<>();
            response.put("message", "删除成功");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            Map<String, String> response = new HashMap<>();
            response.put("message", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
    }
}

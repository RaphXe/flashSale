package com.raph.seckill.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

import com.raph.seckill.entity.SeckillGoods;
import com.raph.seckill.service.SeckillGoodsService;

@RestController
@RequestMapping("/api/seckill/goods")
public class SeckillGoodsController {

    private final SeckillGoodsService seckillGoodsService;

    public SeckillGoodsController(SeckillGoodsService seckillGoodsService) {
        this.seckillGoodsService = seckillGoodsService;
    }

    @GetMapping
    public ResponseEntity<List<SeckillGoods>> list(
            @RequestParam(required = false) Long activityId,
            @RequestParam(required = false) Integer status
    ) {
        return ResponseEntity.ok(seckillGoodsService.queryGoods(activityId, status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable Long id) {
        Optional<SeckillGoods> goodsOptional = seckillGoodsService.findById(id);
        if (goodsOptional.isEmpty()) {
            return errorResponse(HttpStatus.NOT_FOUND, "秒杀商品不存在");
        }
        return ResponseEntity.ok(goodsOptional.get());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody SeckillGoods payload) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(seckillGoodsService.create(payload));
        } catch (IllegalArgumentException ex) {
            return errorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody SeckillGoods payload) {
        try {
            return ResponseEntity.ok(seckillGoodsService.update(id, payload));
        } catch (IllegalArgumentException ex) {
            HttpStatus status = ex.getMessage() != null && ex.getMessage().contains("不存在")
                    ? HttpStatus.NOT_FOUND
                    : HttpStatus.BAD_REQUEST;
            return errorResponse(status, ex.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            seckillGoodsService.delete(id);
            Map<String, String> response = new HashMap<>();
            response.put("message", "删除成功");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return errorResponse(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    private ResponseEntity<Map<String, String>> errorResponse(HttpStatus status, String message) {
        Map<String, String> response = new HashMap<>();
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }
}

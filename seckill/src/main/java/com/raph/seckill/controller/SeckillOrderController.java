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

import com.raph.seckill.dto.CreateSeckillOrderRequest;
import com.raph.seckill.dto.UpdateSeckillOrderRequest;
import com.raph.seckill.entity.SeckillOrder;
import com.raph.seckill.service.SeckillOrderAsyncService;
import com.raph.seckill.service.SeckillOrderService;

@RestController
@RequestMapping("/api/seckill/order")
public class SeckillOrderController {

    private final SeckillOrderService seckillOrderService;
    private final SeckillOrderAsyncService seckillOrderAsyncService;

    public SeckillOrderController(SeckillOrderService seckillOrderService,
                                  SeckillOrderAsyncService seckillOrderAsyncService) {
        this.seckillOrderService = seckillOrderService;
        this.seckillOrderAsyncService = seckillOrderAsyncService;
    }

    @GetMapping
    public ResponseEntity<List<SeckillOrder>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long activityId,
            @RequestParam(required = false) Integer status
    ) {
        return ResponseEntity.ok(seckillOrderService.queryOrders(userId, activityId, status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable Long id) {
        Optional<SeckillOrder> orderOptional = seckillOrderService.findById(id);
        if (orderOptional.isEmpty()) {
            return errorResponse(HttpStatus.NOT_FOUND, "秒杀订单不存在");
        }
        return ResponseEntity.ok(orderOptional.get());
    }

    @GetMapping("/no/{seckillOrderNo}")
    public ResponseEntity<?> detailByOrderNo(@PathVariable String seckillOrderNo) {
        Optional<SeckillOrder> orderOptional = seckillOrderService.findByOrderNo(seckillOrderNo);
        if (orderOptional.isEmpty()) {
            return errorResponse(HttpStatus.NOT_FOUND, "秒杀订单不存在");
        }
        return ResponseEntity.ok(orderOptional.get());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateSeckillOrderRequest request) {
        try {
            // 异步创建订单，委托给kafka消费者处理，快速响应请求，提升用户体验
            String orderNo = seckillOrderAsyncService.submitCreateOrder(request);
            Map<String, String> response = new HashMap<>();
            // 立即响应订单已受理
            response.put("message", "下单请求已受理，正在排队处理");
            // 同步返回订单号，前端可以据此轮询订单状态
            response.put("seckillOrderNo", orderNo);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (IllegalArgumentException ex) {
            return errorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody UpdateSeckillOrderRequest request) {
        try {
            return ResponseEntity.ok(seckillOrderService.update(id, request));
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
            seckillOrderService.delete(id);
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

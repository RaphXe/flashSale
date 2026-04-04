package com.raph.order.controller;

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

import com.raph.order.dto.CreateOrderRequest;
import com.raph.order.dto.UpdateOrderRequest;
import com.raph.order.entity.Order;
import com.raph.order.service.OrderAsyncService;
import com.raph.order.service.OrderService;

@RestController
@RequestMapping("/api/order")
public class OrderController {

    private final OrderService orderService;
    private final OrderAsyncService orderAsyncService;

    public OrderController(OrderService orderService, OrderAsyncService orderAsyncService) {
        this.orderService = orderService;
        this.orderAsyncService = orderAsyncService;
    }

    @GetMapping
    public ResponseEntity<List<Order>> list(@RequestParam(required = false) Long userId) {
        return ResponseEntity.ok(orderService.queryOrders(userId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable Long id) {
        Optional<Order> orderOptional = orderService.findById(id);
        if (orderOptional.isEmpty()) {
            return errorResponse(HttpStatus.NOT_FOUND, "订单不存在");
        }
        return ResponseEntity.ok(orderOptional.get());
    }

    @GetMapping("/no/{orderNo}")
    public ResponseEntity<?> detailByOrderNo(@PathVariable String orderNo) {
        Optional<Order> orderOptional = orderService.findByOrderNo(orderNo);
        if (orderOptional.isEmpty()) {
            return errorResponse(HttpStatus.NOT_FOUND, "订单不存在");
        }
        return ResponseEntity.ok(orderOptional.get());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateOrderRequest request) {
        try {
            String orderNo = orderAsyncService.submitCreateOrder(request);
            Map<String, String> response = new HashMap<>();
            response.put("message", "下单请求已受理，正在排队处理");
            response.put("orderNo", orderNo);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (IllegalArgumentException ex) {
            return errorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody UpdateOrderRequest request) {
        try {
            return ResponseEntity.ok(orderService.update(id, request));
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
            orderService.delete(id);
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

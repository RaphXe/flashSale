package com.raph.order.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

@Data
public class CreateOrderMessage {

    private String orderNo;
    private Long userId;
    private Integer type;
    private Integer orderStatus;
    private Integer payStatus;
    private LocalDateTime expireTime;
    private List<OrderItemRequest> items;
}

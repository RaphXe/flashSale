package com.raph.order.dto;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class OrderTimeoutMessage {

    private String orderNo;
    private Long userId;
    private LocalDateTime expireTime;
}

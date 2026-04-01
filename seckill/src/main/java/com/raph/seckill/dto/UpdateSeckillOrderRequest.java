package com.raph.seckill.dto;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class UpdateSeckillOrderRequest {

    private Integer status;
    private Long orderId;
    private LocalDateTime expireTime;
}

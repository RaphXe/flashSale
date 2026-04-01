package com.raph.seckill.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

@Data
public class CreateSeckillOrderRequest {

    private Long activityId;
    private Long goodsId;
    private Long userId;
    private Integer quantity;
    private BigDecimal seckillPrice;
    private LocalDateTime expireTime;
    private Integer status;
}

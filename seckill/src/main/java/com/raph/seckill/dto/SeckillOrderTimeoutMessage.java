package com.raph.seckill.dto;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class SeckillOrderTimeoutMessage {

    private String seckillOrderNo;
    private Long activityId;
    private Long goodsId;
    private Integer quantity;
    private LocalDateTime expireTime;
}

package com.raph.seckill.dto;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class SeckillActivityTaskMessage {

    private Long activityId;

    // 任务类型：WARMUP / START / END / SETTLE
    private String taskType;

    // 任务计划执行时间
    private LocalDateTime executeAt;
}

package com.raph.seckill.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "seckill_local_message")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillLocalMessage {

    @Id
    private Long id;

    @Column(name = "message_id", nullable = false, unique = true, length = 64)
    private String messageId;

    @Column(name = "biz_type", nullable = false, length = 64)
    private String bizType;

    @Column(name = "biz_key", nullable = false, length = 128)
    private String bizKey;

    @Column(name = "seckill_order_no", nullable = false, length = 64)
    private String seckillOrderNo;

    @Column(name = "activity_id", nullable = false)
    private Long activityId;

    @Column(name = "goods_id", nullable = false)
    private Long goodsId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "destination_type", nullable = false)
    private Integer destinationType;

    @Column(name = "destination", nullable = false)
    private String destination;

    @Column(name = "routing_key")
    private String routingKey;

    @Column(name = "payload", nullable = false, columnDefinition = "json")
    private String payload;

    @Column(name = "status", nullable = false)
    private Integer status;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "max_retry_count", nullable = false)
    private Integer maxRetryCount;

    @Column(name = "next_retry_time", nullable = false)
    private LocalDateTime nextRetryTime;

    @Column(name = "last_error", length = 1024)
    private String lastError;

    @Column(name = "sent_time")
    private LocalDateTime sentTime;

    @Column(name = "confirmed_time")
    private LocalDateTime confirmedTime;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;
}

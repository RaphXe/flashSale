package com.raph.seckill.entity;

import java.math.BigDecimal;
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
@Table(name = "seckill_order")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrder {

    @Id
    private Long id;

    @Column(name = "seckill_order_no", unique = true, length = 64)
    private String seckillOrderNo;

    @Column(name = "activity_id", nullable = false)
    private Long activityId;

    @Column(name = "goods_id", nullable = false)
    private Long goodsId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "seckill_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal seckillPrice;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    // 0: 待创建普通订单, 1: 已创建普通订单, 2: 已超时, 3: 已取消
    @Column(name = "status", nullable = false)
    private Integer status;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "expire_time", nullable = false)
    private LocalDateTime expireTime;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

}

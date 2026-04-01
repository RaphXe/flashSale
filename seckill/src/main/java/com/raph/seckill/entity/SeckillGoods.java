package com.raph.seckill.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonBackReference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "seckill_goods")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillGoods {
    @Id
    private Long id;

    @JsonBackReference
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id", nullable = false, insertable = false, updatable = false)
    private SeckillActivity seckillActivity;

    @Column(name = "activity_id")
    private Long activityId; // 关联的秒杀活动ID

    @Column(name = "goods_id")
    private Long goodsId; // 关联的商品ID

    @Column(name = "seckill_price")
    private BigDecimal seckillPrice; // 秒杀价格

    @Column(name = "seckill_stock")
    private Integer seckillStock; // 秒杀库存

    @Column(name = "available_stock")
    private Integer availableStock; // 可用库存（秒杀库存 - 已锁定库存）

    @Column(name = "lock_stock")
    private Integer lockStock; // 已锁定库存（正在进行秒杀的订单数量）

    @Column(name = "per_user_limit")
    private Integer perUserLimit; // 每人限购数量

    @Version        
    @Column(name = "version")
    private Integer version; // 乐观锁版本号

    @Column(name = "status")
    private Integer status; // 0: 未开始, 1: 进行中, 2: 已结束

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;
}

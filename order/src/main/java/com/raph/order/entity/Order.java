package com.raph.order.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonManagedReference;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "`order`")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    private Long id;

    @Column(name = "order_no", unique = true, length = 255)
    private String orderNo;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "type")
    private Integer type;

    // 0: 待支付, 1: 已支付, 2: 已取消, 3: 已完成
    @Column(name = "order_status")
    private Integer orderStatus;

    @Column(name = "amount", precision = 10, scale = 2)
    private BigDecimal amount;

    // 0: 待支付, 1: 已支付,
    @Column(name = "pay_status")
    private Integer payStatus;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "pay_time")
    private LocalDateTime payTime;

    @Column(name = "expire_time")
    private LocalDateTime expireTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @JsonManagedReference
    @Builder.Default
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();
}

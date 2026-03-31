package com.raph.seckill.entity;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@Entity
@Table(name = "seckill_activity")
@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SeckillActivity {
    @Id
    private Long id;

    private String name;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    private Integer status; // 0: 未开始, 1: 进行中, 2: 已结束

    @Column(name = "limit_per_person")
    private Integer limitPerPerson; // 每人限购数量

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @JsonManagedReference
    @Builder.Default
    @OneToMany(mappedBy = "seckillActivity", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<SeckillGoods> seckillGoods = new ArrayList<>();
}

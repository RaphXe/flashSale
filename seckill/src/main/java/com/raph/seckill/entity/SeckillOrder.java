package com.raph.seckill.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "seckill_order")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrder {
    @Id
    private Long id;


}

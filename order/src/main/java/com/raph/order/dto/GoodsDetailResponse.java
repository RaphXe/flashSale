package com.raph.order.dto;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class GoodsDetailResponse {

    private Long id;
    private BigDecimal price;
    private Integer status;
}

package com.raph.order.dto;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class OrderItemRequest {

    private Long goodsId;
    private BigDecimal buyPrice;
    private Integer quantity;
    private Long activityId;
}

package com.raph.order.dto;

import java.util.Map;

import lombok.Data;

@Data
public class StockLockAdjustRequest {

    private Map<Long, Integer> quantityDeltaByGoods;
}

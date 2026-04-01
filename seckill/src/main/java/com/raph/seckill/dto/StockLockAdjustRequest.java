package com.raph.seckill.dto;

import java.util.Map;

import lombok.Data;

@Data
public class StockLockAdjustRequest {

    private Map<Long, Integer> quantityDeltaByGoods;
}

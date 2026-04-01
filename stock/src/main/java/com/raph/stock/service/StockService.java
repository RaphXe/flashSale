package com.raph.stock.service;

import com.raph.stock.entity.Stock;
import com.raph.stock.repository.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import java.util.Optional;

@Service
public class StockService {

    private final StockRepository stockRepository;

    public StockService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    public List<Stock> queryStock(Long goodsId) {
        if (goodsId != null) {
            return stockRepository.findByGoodsId(goodsId);
        }
        return stockRepository.findAll();
    }

    public Optional<Stock> findById(Long id) {
        return stockRepository.findById(id);
    }

    @Transactional
    public Stock create(Stock stock) {
        stock.setId(null);
        stock.setUpdateTime(LocalDateTime.now());
        if (stock.getVersion() == null) {
            stock.setVersion(0);
        }
        return stockRepository.save(stock);
    }

    @Transactional
    public Stock update(Long id, Stock payload) {
        if (payload.getVersion() == null) {
            throw new IllegalArgumentException("更新库存时必须传入 version");
        }

        LocalDateTime now = LocalDateTime.now();
        int affected = stockRepository.updateByIdAndVersion(
                id,
                payload.getVersion(),
                payload.getGoodsId(),
                payload.getTotalStock(),
                payload.getAvailableStock(),
                payload.getLockedStock(),
                now
        );

        if (affected == 0) {
            if (!stockRepository.existsById(id)) {
                throw new IllegalArgumentException("库存不存在");
            }
            throw new IllegalArgumentException("库存版本冲突，请刷新后重试");
        }

        return stockRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("库存不存在"));
    }

    @Transactional
    public void delete(Long id) {
        if (!stockRepository.existsById(id)) {
            throw new IllegalArgumentException("库存不存在");
        }
        stockRepository.deleteById(id);
    }

    @Transactional
    public void adjustLockStock(Map<Long, Integer> quantityDeltaByGoods) {
        if (quantityDeltaByGoods == null || quantityDeltaByGoods.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<Long, Integer> entry : quantityDeltaByGoods.entrySet()) {
            Long goodsId = entry.getKey();
            Integer delta = entry.getValue();

            if (goodsId == null || delta == null || delta == 0) {
                continue;
            }

            // 悲观锁
            Stock stock = stockRepository.findByGoodsIdForUpdate(goodsId)
                    .orElseThrow(() -> new IllegalArgumentException("商品库存不存在, goodsId=" + goodsId));

            if (delta > 0) {
                if (stock.getAvailableStock() < delta) {
                    throw new IllegalArgumentException("库存不足, goodsId=" + goodsId);
                }
                stock.setAvailableStock(stock.getAvailableStock() - delta);
                stock.setLockedStock(stock.getLockedStock() + delta);
            } else {
                int release = -delta;
                if (stock.getLockedStock() < release) {
                    throw new IllegalArgumentException("锁定库存不足以释放, goodsId=" + goodsId);
                }
                stock.setAvailableStock(stock.getAvailableStock() + release);
                stock.setLockedStock(stock.getLockedStock() - release);
            }

            stock.setUpdateTime(now);
            stockRepository.save(stock);
        }
    }
}

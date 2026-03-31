package com.raph.goods.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.raph.goods.entity.Goods;
import com.raph.goods.repository.GoodsRepository;

@Service
public class GoodsService {

    private final GoodsRepository goodsRepository;

    public GoodsService(GoodsRepository goodsRepository) {
        this.goodsRepository = goodsRepository;
    }

    public List<Goods> queryGoods(Integer status, String keyword, BigDecimal minPrice, BigDecimal maxPrice) {
        boolean hasKeyword = StringUtils.hasText(keyword);

        if (status != null && hasKeyword) {
            return goodsRepository.searchByStatusAndKeyword(status, keyword.trim());
        }
        if (status != null) {
            return goodsRepository.findAllByStatusOrderByUpdateTimeDesc(status);
        }
        if (minPrice != null && maxPrice != null) {
            return goodsRepository.findByPriceRange(minPrice, maxPrice);
        }
        if (hasKeyword) {
            return goodsRepository.findByNameContaining(keyword.trim());
        }
        return goodsRepository.findAll();
    }

    public Optional<Goods> findById(Long id) {
        return goodsRepository.findById(id);
    }

    public List<Goods> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return goodsRepository.findAllById(ids);
    }

    @Transactional
    public Goods create(Goods goods) {
        LocalDateTime now = LocalDateTime.now();
        goods.setId(null);
        goods.setCreateTime(now);
        goods.setUpdateTime(now);
        return goodsRepository.save(goods);
    }

    @Transactional
    public Goods update(Long id, Goods payload) {
        Goods existing = goodsRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在"));

        existing.setName(payload.getName());
        existing.setDescription(payload.getDescription());
        existing.setPrice(payload.getPrice());
        existing.setStatus(payload.getStatus());
        existing.setUpdateTime(LocalDateTime.now());

        return goodsRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        if (!goodsRepository.existsById(id)) {
            throw new IllegalArgumentException("商品不存在");
        }
        goodsRepository.deleteById(id);
    }
}

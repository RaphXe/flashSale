package com.raph.stock.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.raph.stock.entity.Stock;

@Repository
public interface StockRepository extends JpaRepository<Stock, Long> {

    List<Stock> findByGoodsId(Long goodsId);

    @Modifying
    @Query("""
            UPDATE Stock s
            SET s.goodsId = :goodsId,
                s.totalStock = :totalStock,
                s.availableStock = :availableStock,
                s.lockedStock = :lockedStock,
                s.updateTime = :updateTime,
                s.version = s.version + 1
            WHERE s.id = :id AND s.version = :version
            """)
    int updateByIdAndVersion(
            @Param("id") Long id,
            @Param("version") Integer version,
            @Param("goodsId") Long goodsId,
            @Param("totalStock") Integer totalStock,
            @Param("availableStock") Integer availableStock,
            @Param("lockedStock") Integer lockedStock,
            @Param("updateTime") java.time.LocalDateTime updateTime
    );
}

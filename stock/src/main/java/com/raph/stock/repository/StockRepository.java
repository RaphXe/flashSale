package com.raph.stock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.raph.stock.entity.Stock;

import jakarta.persistence.LockModeType;

@Repository
public interface StockRepository extends JpaRepository<Stock, Long> {

    List<Stock> findByGoodsId(Long goodsId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Stock s WHERE s.goodsId = :goodsId")
    Optional<Stock> findByGoodsIdForUpdate(@Param("goodsId") Long goodsId);

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

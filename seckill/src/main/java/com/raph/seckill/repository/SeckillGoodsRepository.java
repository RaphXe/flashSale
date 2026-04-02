package com.raph.seckill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.raph.seckill.entity.SeckillGoods;

import jakarta.persistence.LockModeType;

@Repository
public interface SeckillGoodsRepository extends JpaRepository<SeckillGoods, Long> {

    @Query("SELECT g.id FROM SeckillGoods g")
    List<Long> findAllIds();

    List<SeckillGoods> findByActivityId(Long activityId);

    List<SeckillGoods> findByActivityIdOrderByUpdateTimeDesc(Long activityId);

    Optional<SeckillGoods> findByActivityIdAndGoodsId(Long activityId, Long goodsId);

    List<SeckillGoods> findByStatus(Integer status);

    List<SeckillGoods> findByStatusOrderByUpdateTimeDesc(Integer status);

    List<SeckillGoods> findByActivityIdAndStatusOrderByUpdateTimeDesc(Long activityId, Integer status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM SeckillGoods g WHERE g.activityId = :activityId")
    List<SeckillGoods> findByActivityIdForUpdate(@Param("activityId") Long activityId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM SeckillGoods g WHERE g.activityId = :activityId AND g.goodsId = :goodsId")
    Optional<SeckillGoods> findByActivityIdAndGoodsIdForUpdate(
            @Param("activityId") Long activityId,
            @Param("goodsId") Long goodsId);
}

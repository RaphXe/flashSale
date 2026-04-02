package com.raph.seckill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.raph.seckill.entity.SeckillOrder;

import jakarta.persistence.LockModeType;

@Repository
public interface SeckillOrderRepository extends JpaRepository<SeckillOrder, Long> {

    Optional<SeckillOrder> findBySeckillOrderNo(String seckillOrderNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM SeckillOrder o WHERE o.seckillOrderNo = :seckillOrderNo")
    Optional<SeckillOrder> findBySeckillOrderNoForUpdate(@Param("seckillOrderNo") String seckillOrderNo);

    Optional<SeckillOrder> findByActivityIdAndGoodsIdAndUserId(Long activityId, Long goodsId, Long userId);

    List<SeckillOrder> findByUserIdOrderByCreateTimeDesc(Long userId);

    List<SeckillOrder> findByUserIdAndStatusOrderByCreateTimeDesc(Long userId, Integer status);

    List<SeckillOrder> findByActivityIdOrderByCreateTimeDesc(Long activityId);

    List<SeckillOrder> findByActivityIdAndStatusOrderByCreateTimeDesc(Long activityId, Integer status);

    List<SeckillOrder> findByStatusOrderByCreateTimeDesc(Integer status);

    List<SeckillOrder> findAllByOrderByCreateTimeDesc();
}

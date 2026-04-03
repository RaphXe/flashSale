package com.raph.seckill.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.raph.seckill.entity.SeckillActivity;

@Repository
public interface SeckillActivityRepository extends JpaRepository<SeckillActivity, Long> {

    List<SeckillActivity> findByStatusOrderByStartTimeAsc(Integer status);

    List<SeckillActivity> findAllByOrderByStartTimeDesc();

    @Query("SELECT a FROM SeckillActivity a WHERE a.startTime <= :now AND a.endTime >= :now ORDER BY a.startTime ASC")
    List<SeckillActivity> findCurrentActivities(@Param("now") LocalDateTime now);

    @Query("SELECT a FROM SeckillActivity a WHERE a.startTime >= :fromTime AND a.startTime <= :toTime ORDER BY a.startTime ASC")
    List<SeckillActivity> findByStartTimeBetweenOrderByStartTimeAsc(
            @Param("fromTime") LocalDateTime fromTime,
            @Param("toTime") LocalDateTime toTime);
}

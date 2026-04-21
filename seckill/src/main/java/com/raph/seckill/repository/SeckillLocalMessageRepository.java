package com.raph.seckill.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.raph.seckill.entity.SeckillLocalMessage;

@Repository
public interface SeckillLocalMessageRepository extends JpaRepository<SeckillLocalMessage, Long> {

    List<SeckillLocalMessage> findTop100ByStatusInAndNextRetryTimeLessThanEqualOrderByCreateTimeAsc(
            Collection<Integer> statuses,
            LocalDateTime nextRetryTime
    );

    boolean existsByBizTypeAndBizKey(String bizType, String bizKey);

    @Transactional
    @Modifying
    @Query("""
            UPDATE SeckillLocalMessage m
               SET m.status = :sendingStatus,
                   m.version = m.version + 1,
                   m.updateTime = :now
             WHERE m.id = :id
               AND m.version = :version
               AND m.status IN :claimableStatuses
            """)
    int claimForSending(@Param("id") Long id,
                        @Param("version") Integer version,
                        @Param("claimableStatuses") Collection<Integer> claimableStatuses,
                        @Param("sendingStatus") Integer sendingStatus,
                        @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("""
            UPDATE SeckillLocalMessage m
               SET m.status = :status,
                   m.sentTime = :sentTime,
                   m.confirmedTime = :confirmedTime,
                   m.lastError = NULL,
                   m.updateTime = :now
             WHERE m.id = :id
            """)
    int markSent(@Param("id") Long id,
                 @Param("status") Integer status,
                 @Param("sentTime") LocalDateTime sentTime,
                 @Param("confirmedTime") LocalDateTime confirmedTime,
                 @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("""
            UPDATE SeckillLocalMessage m
               SET m.status = :status,
                   m.retryCount = :retryCount,
                   m.nextRetryTime = :nextRetryTime,
                   m.lastError = :lastError,
                   m.updateTime = :now
             WHERE m.id = :id
            """)
    int markFailed(@Param("id") Long id,
                   @Param("status") Integer status,
                   @Param("retryCount") Integer retryCount,
                   @Param("nextRetryTime") LocalDateTime nextRetryTime,
                   @Param("lastError") String lastError,
                   @Param("now") LocalDateTime now);
}

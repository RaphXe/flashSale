package com.raph.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.raph.order.entity.Order;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNo(String orderNo);

    @EntityGraph(attributePaths = "items")
    @Query("SELECT o FROM Order o WHERE (:userId IS NULL OR o.userId = :userId) ORDER BY o.createTime DESC")
    List<Order> queryOrders(@Param("userId") Long userId);

    @EntityGraph(attributePaths = "items")
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findWithItemsById(@Param("id") Long id);
}

package com.raph.goods.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.raph.goods.entity.Goods;

@Repository
public interface GoodsRepository extends JpaRepository<Goods, Long> {

    @Query("SELECT g.id FROM Goods g")
    List<Long> findAllIds();

    List<Goods> findByStatus(Integer status);

    List<Goods> findByNameContaining(String keyword);

    List<Goods> findByNameContainingOrDescriptionContaining(String nameKeyword, String descriptionKeyword);

    @Query("SELECT g FROM Goods g WHERE g.status = :status ORDER BY g.updateTime DESC")
    List<Goods> findAllByStatusOrderByUpdateTimeDesc(@Param("status") Integer status);

    @Query("SELECT g FROM Goods g WHERE g.price BETWEEN :minPrice AND :maxPrice ORDER BY g.price ASC")
    List<Goods> findByPriceRange(@Param("minPrice") BigDecimal minPrice, @Param("maxPrice") BigDecimal maxPrice);

    @Query(value = "SELECT * FROM goods WHERE status = :status AND name LIKE CONCAT('%', :keyword, '%') ORDER BY update_time DESC", nativeQuery = true)
    List<Goods> searchByStatusAndKeyword(@Param("status") Integer status, @Param("keyword") String keyword);
}

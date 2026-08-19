package com.seckill.repository;

import com.seckill.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByMerchantIdAndStatusOrderByCreatedAtDesc(Long merchantId, Integer status);
    List<Product> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);

    @Modifying
    @Query("UPDATE Product p SET p.remainStock = p.remainStock - 1 WHERE p.id = :id AND p.remainStock > 0")
    int decrementRemainStock(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Product p SET p.remainStock = p.remainStock + 1 WHERE p.id = :id")
    int incrementRemainStock(@Param("id") Long id);
}

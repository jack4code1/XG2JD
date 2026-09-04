package com.seckill.repository;

import com.seckill.model.ShopReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShopReviewRepository extends JpaRepository<ShopReview, Long> {
    List<ShopReview> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
}

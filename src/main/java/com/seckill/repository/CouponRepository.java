package com.seckill.repository;

import com.seckill.model.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
    List<Coupon> findByStatus(Integer status);
}
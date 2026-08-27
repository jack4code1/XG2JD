package com.seckill.repository;

import com.seckill.model.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.time.LocalDateTime;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
    List<Coupon> findByStatus(Integer status);
    List<Coupon> findByCouponNameStartingWith(String prefix);
    List<Coupon> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
    List<Coupon> findByStatusAndStartTimeBefore(Integer status, LocalDateTime time);
    List<Coupon> findByStatusAndEndTimeBefore(Integer status, LocalDateTime time);

    @Modifying
    @Query("UPDATE Coupon c SET c.remainStock = c.remainStock - 1 " +
           "WHERE c.id = :couponId AND c.remainStock > 0")
    int decrementRemainStock(@Param("couponId") Long couponId);

    @Modifying
    @Query("UPDATE Coupon c SET c.remainStock = c.remainStock + 1 " +
           "WHERE c.id = :couponId AND c.remainStock < c.totalStock")
    int incrementRemainStock(@Param("couponId") Long couponId);
}

package com.seckill.repository;

import com.seckill.model.CouponVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CouponVersionRepository extends JpaRepository<CouponVersion, Long> {
    List<CouponVersion> findByCouponIdOrderByVersionNoDesc(Long couponId);
    Optional<CouponVersion> findByCouponIdAndVersionNo(Long couponId, Integer versionNo);
}

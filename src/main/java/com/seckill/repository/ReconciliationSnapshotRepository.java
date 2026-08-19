package com.seckill.repository;

import com.seckill.model.ReconciliationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

public interface ReconciliationSnapshotRepository extends JpaRepository<ReconciliationSnapshot, Long> {
    Optional<ReconciliationSnapshot> findBySnapDateAndCouponId(LocalDate snapDate, Long couponId);
}
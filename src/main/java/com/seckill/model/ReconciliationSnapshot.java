package com.seckill.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "reconciliation_snapshot")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snap_date", nullable = false)
    private LocalDate snapDate;

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Column(name = "redis_deduct", nullable = false)
    private Integer redisDeduct;

    @Column(name = "mysql_created", nullable = false)
    private Integer mysqlCreated;

    @Column(name = "mysql_paid", nullable = false)
    private Integer mysqlPaid;

    @Column(name = "diff_detail", columnDefinition = "JSON")
    private String diffDetail;

    @Column(name = "reconciled_at")
    private LocalDateTime reconciledAt;
}
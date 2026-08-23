package com.seckill.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Immutable business history for a published coupon configuration. */
@Entity
@Table(name = "coupon_version", indexes = {
        @Index(name = "idx_coupon_version_coupon", columnList = "coupon_id,version_no"),
        @Index(name = "idx_coupon_version_merchant", columnList = "merchant_id,created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "coupon_id", nullable = false)
    private Long couponId;
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;
    @Column(nullable = false, length = 32)
    private String action;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;
    @Column(name = "created_by")
    private Long createdBy;
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void beforeInsert() { createdAt = LocalDateTime.now(); }
}

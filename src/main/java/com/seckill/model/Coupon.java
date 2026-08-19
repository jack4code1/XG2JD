package com.seckill.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "t_coupon")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coupon_name", nullable = false, length = 128)
    private String couponName;

    @Column(name = "coupon_desc", length = 512)
    private String couponDesc;

    @Column(name = "total_stock", nullable = false)
    private Integer totalStock;

    @Column(name = "remain_stock", nullable = false)
    private Integer remainStock;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Column(name = "per_user_max", nullable = false)
    private Integer perUserMax;

    @Column(nullable = false)
    private Integer status; // 0=未开始 1=进行中 2=已结束

    @Version
    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
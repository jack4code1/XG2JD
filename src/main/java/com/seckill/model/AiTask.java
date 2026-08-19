package com.seckill.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_task", indexes = {
        @Index(name = "idx_ai_task_merchant_time", columnList = "merchant_id,created_at"),
        @Index(name = "idx_ai_task_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_no", nullable = false, unique = true, length = 64)
    private String taskNo;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(nullable = false, length = 512)
    private String query;

    @Column(name = "action_type", nullable = false, length = 32)
    private String actionType;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "target_coupon_id")
    private Long targetCouponId;

    @Lob
    @Column(name = "proposal_json", nullable = false, columnDefinition = "TEXT")
    private String proposalJson;

    @Lob
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "requires_confirmation", nullable = false)
    private Boolean requiresConfirmation;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

package com.seckill.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_audit_log", indexes = @Index(name = "idx_ai_audit_merchant_time", columnList = "merchant_id,created_at"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(nullable = false, length = 512)
    private String query;

    @Column(length = 32)
    private String intent;

    @Column(name = "elapsed_ms")
    private Long elapsedMs;

    @Column(nullable = false)
    private Boolean degraded;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }
}

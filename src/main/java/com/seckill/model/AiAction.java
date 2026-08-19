package com.seckill.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_action", indexes = {
        @Index(name = "idx_ai_action_task_time", columnList = "task_id,created_at"),
        @Index(name = "idx_ai_action_merchant", columnList = "merchant_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "action_type", nullable = false, length = 32)
    private String actionType;

    @Column(nullable = false, length = 32)
    private String status;

    @Lob
    @Column(name = "input_json", nullable = false, columnDefinition = "TEXT")
    private String inputJson;

    @Lob
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

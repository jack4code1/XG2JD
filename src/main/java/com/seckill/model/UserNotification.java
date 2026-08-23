package com.seckill.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_notification", indexes = @Index(name = "idx_notification_recipient", columnList = "recipient_id,read_at,created_at"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UserNotification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "recipient_id", nullable = false) private Long recipientId;
    @Column(nullable = false, length = 32) private String type;
    @Column(nullable = false, length = 128) private String title;
    @Column(nullable = false, length = 512) private String content;
    @Column(name = "read_at") private LocalDateTime readAt;
    @Column(name = "created_at", updatable = false) private LocalDateTime createdAt;
    @PrePersist void beforeInsert() { createdAt = LocalDateTime.now(); }
}

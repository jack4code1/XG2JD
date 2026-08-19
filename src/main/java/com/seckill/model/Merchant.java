package com.seckill.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "t_merchant")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Merchant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "shop_name", nullable = false, length = 128)
    private String shopName;

    @Column(name = "shop_desc", length = 512)
    private String shopDesc;

    @Column(length = 64)
    private String category; // 餐饮/服饰/数码/美妆/其他

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist protected void onCreate() { createdAt = LocalDateTime.now(); }
}
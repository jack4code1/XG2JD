package com.seckill.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Value
@Builder
public class OrderView {
    Long id;
    String orderNo;
    Long couponId;
    Long productId;
    String couponName;
    String productName;
    String shopName;
    String status;
    BigDecimal amount;
    BigDecimal originalAmount;
    BigDecimal discountAmount;
    String orderType;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}

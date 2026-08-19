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
    String couponName;
    String shopName;
    String status;
    BigDecimal amount;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}

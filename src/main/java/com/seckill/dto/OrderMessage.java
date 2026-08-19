package com.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderMessage {
    private String orderNo;
    private Long userId;
    private Long couponId;
    private BigDecimal amount;
    private int userWeight;
    private long timestamp;
}
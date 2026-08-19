package com.seckill.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class SeckillResultResponse {
    boolean success;
    String orderNo;
    String status;
    String message;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}

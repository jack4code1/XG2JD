package com.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillResponse {

    private boolean success;
    private String orderNo;
    private String message;
    private int userWeight; // 用户本次分配的权重

    public static SeckillResponse ok(String orderNo, int weight) {
        return SeckillResponse.builder()
                .success(true)
                .orderNo(orderNo)
                .message("抢券成功")
                .userWeight(weight)
                .build();
    }

    public static SeckillResponse fail(String message) {
        return SeckillResponse.builder()
                .success(false)
                .message(message)
                .build();
    }
}

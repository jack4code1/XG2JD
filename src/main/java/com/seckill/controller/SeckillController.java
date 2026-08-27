package com.seckill.controller;

import com.seckill.common.Result;
import com.seckill.common.ResultCode;
import com.seckill.dto.SeckillRequest;
import com.seckill.dto.SeckillResponse;
import com.seckill.dto.SeckillResultResponse;
import com.seckill.repository.OrderRepository;
import com.seckill.service.SeckillService;
import com.seckill.util.DeviceFingerprintUtil;
import com.seckill.util.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 秒杀接口
 *
 * 面试可讲：
 * - POST /seckill/execute — 核心秒杀接口，全链路漏斗过滤
 * - 必须登录（拦截器前置校验）
 * - 设备指纹从前端 Header 传入
 */
@Slf4j
@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;
    private final OrderRepository orderRepository;

    /**
     * 执行秒杀
     */
    @PostMapping("/execute")
    public Result<SeckillResponse> execute(@RequestBody SeckillRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }

        // 设置设备指纹到线程上下文
        if (request.getDeviceFingerprint() != null) {
            DeviceFingerprintUtil.setCurrentDeviceFingerprint(request.getDeviceFingerprint());
        }

        try {
            return Result.ok(seckillService.executeSeckill(userId, request.getCouponId()));
        } finally {
            DeviceFingerprintUtil.clear();
        }
    }

    /**
     * 查询秒杀结果（供前端轮询）
     */
    @GetMapping("/result/{orderNo}")
    public Result<SeckillResultResponse> queryResult(@PathVariable String orderNo) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }

        SeckillResultResponse response = orderRepository.findByOrderNo(orderNo)
                .filter(order -> userId.equals(order.getUserId()))
                .map(order -> SeckillResultResponse.builder()
                        .success(true)
                        .orderNo(order.getOrderNo())
                        .status(order.getStatus())
                        .message(resultMessage(order.getStatus()))
                        .createdAt(order.getCreatedAt())
                        .updatedAt(order.getUpdatedAt())
                        .build())
                .orElseGet(() -> SeckillResultResponse.builder()
                        .success(false).orderNo(orderNo).status("NOT_FOUND").message("订单还在创建中，请稍后重试").build());
        return Result.ok(response);
    }

    private String resultMessage(String status) {
        return switch (status) {
            case "CREATED" -> "订单已创建，等待后续处理";
            case "PAID" -> "订单已支付";
            case "CANCELED" -> "订单已取消";
            case "EXPIRED" -> "订单已过期";
            default -> "订单状态：" + status;
        };
    }
}

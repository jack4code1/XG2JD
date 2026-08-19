package com.seckill.controller;

import com.seckill.dto.SeckillRequest;
import com.seckill.dto.SeckillResponse;
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

    /**
     * 执行秒杀
     */
    @PostMapping("/execute")
    public SeckillResponse execute(@RequestBody SeckillRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return SeckillResponse.fail("请先登录");
        }

        // 设置设备指纹到线程上下文
        if (request.getDeviceFingerprint() != null) {
            DeviceFingerprintUtil.setCurrentDeviceFingerprint(request.getDeviceFingerprint());
        }

        try {
            return seckillService.executeSeckill(userId, request.getCouponId());
        } finally {
            DeviceFingerprintUtil.clear();
        }
    }

    /**
     * 查询秒杀结果（供前端轮询）
     */
    @GetMapping("/result/{orderNo}")
    public SeckillResponse queryResult(@PathVariable String orderNo) {
        // TODO: 实现订单状态查询
        return SeckillResponse.ok(orderNo, 0);
    }
}
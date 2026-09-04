package com.seckill.service;

import com.seckill.model.Order;
import com.seckill.model.ReconciliationSnapshot;
import com.seckill.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 对账服务 — T+1 全量 + 实时异常检测
 *
 * 设计说明：
 * - T+1 全量对账：每日凌晨对比 Redis 扣库存流水 vs MySQL 订单表
 * - 实时异常：订单超时未流转 → 告警
 * - 差异处理：漏单→补创建，超卖→回滚+告警
 * - 参考阿里对账体系设计思路
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final CouponSeckillStateService couponSeckillStateService;
    private final OrderRepository orderRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * T+1 全量对账（每日凌晨2:00执行）
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void dailyReconciliation() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("开始T+1全量对账: date={}", yesterday);

        // 1. 获取昨日创建的订单
        List<Order> orders = orderRepository.findAll(); // 简化，实际按日期过滤

        for (Order order : orders) {
            Long couponId = order.getCouponId();
            // 2. 获取 Redis 中的库存扣减记录
            Integer redisRemain = couponSeckillStateService.currentStock(couponId, -1);

            // 3. 从快照表获取昨日记录
            // TODO: 实现快照表的读写

            log.debug("对账: orderNo={}, couponId={}, redisRemain={}",
                    order.getOrderNo(), couponId, redisRemain);
        }

        log.info("T+1全量对账完成");
    }

    /**
     * 实时异常检测（每30秒）
     */
    @Scheduled(fixedDelay = 30_000)
    public void realtimeDetection() {
        // 检测超时未支付订单（CREATED超过15分钟）
        int expiredCount = orderRepository.expireOrders(
                java.time.LocalDateTime.now().minusMinutes(15));
        if (expiredCount > 0) {
            log.warn("实时异常检测: 发现{}个超时未支付订单", expiredCount);
        }

        // TODO: 检测超卖（同couponId下PAID订单数 > 实际库存）
        // TODO: 检测事件表status=3（失败终态）数量超阈值
    }
}

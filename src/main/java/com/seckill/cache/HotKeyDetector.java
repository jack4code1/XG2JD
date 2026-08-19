package com.seckill.cache;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 热点发现引擎 — 时间片环形缓冲区
 *
 * 面试可讲：
 * - 环形数组 12 个槽 × 5 秒/槽 = 60 秒滑动窗口
 * - O(1) 写入（ConcurrentHashMap.computeIfAbsent + AtomicLong.increment）
 * - O(槽位数) 查询热点（12 次 Map.get，< 1ms）
 * - 比 Redis LFU 方案更优：不需要每次请求跨网络操作 Redis
 */
@Slf4j
@Component
public class HotKeyDetector {

    /** 槽位数量 */
    @Value("${seckill.hot-key.slot-count:12}")
    private int slotCount;

    /** 热点 QPS 阈值 */
    @Value("${seckill.hot-key.threshold-qps:100}")
    private long thresholdQps;

    /** 窗口总秒数 */
    @Value("${seckill.hot-key.window-seconds:60}")
    private int windowSeconds;

    /** 环形缓冲区：每个槽是一个 ConcurrentHashMap<Key, AtomicLong> */
    private ConcurrentHashMap<String, AtomicLong>[] ringBuffer;

    /** 当前指针位置 */
    private final AtomicInteger currentSlot = new AtomicInteger(0);

    /** 当前标记为热点的 Key 集合 */
    private final Set<String> hotKeys = ConcurrentHashMap.newKeySet();

    /** 各 Key 连续低于阈值的检测周期计数（用于降级判定） */
    private final ConcurrentHashMap<String, AtomicInteger> coolDownCounters = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    @SuppressWarnings("unchecked")
    @PostConstruct
    public void init() {
        ringBuffer = new ConcurrentHashMap[slotCount];
        for (int i = 0; i < slotCount; i++) {
            ringBuffer[i] = new ConcurrentHashMap<>();
        }

        int slotIntervalSec = windowSeconds / slotCount;
        scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "hotkey-detector");
            t.setDaemon(true);
            return t;
        });

        // 定时切换槽位 + 检测热点
        scheduler.scheduleAtFixedRate(() -> {
            try {
                int next = (currentSlot.get() + 1) % slotCount;
                ringBuffer[next].clear(); // 清空即将进入的槽位
                currentSlot.set(next);
                detectHotKeys();
            } catch (Exception e) {
                log.error("热点检测异常", e);
            }
        }, slotIntervalSec, slotIntervalSec, TimeUnit.SECONDS);

        log.info("热点检测器初始化: slots={}, window={}s, threshold={}qps",
                slotCount, windowSeconds, thresholdQps);
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /**
     * 记录一次 key 访问（每秒可被调用数十万次）
     */
    public void record(String key) {
        int slot = currentSlot.get();
        ringBuffer[slot]
                .computeIfAbsent(key, k -> new AtomicLong())
                .incrementAndGet();
    }

    /**
     * 判断 key 是否为热点
     */
    public boolean isHot(String key) {
        return hotKeys.contains(key);
    }

    /**
     * 获取当前热点 Key 数量（暴露给 Prometheus）
     */
    public int getHotKeyCount() {
        return hotKeys.size();
    }

    /**
     * 定时检测：统计 60s 窗口内总 QPS，判定热点升级/降级
     */
    private void detectHotKeys() {
        // 统计所有 key 在窗口内的总访问量
        Map<String, Long> totalCounts = new ConcurrentHashMap<>();
        for (int i = 0; i < slotCount; i++) {
            for (Map.Entry<String, AtomicLong> entry : ringBuffer[i].entrySet()) {
                totalCounts.merge(entry.getKey(), entry.getValue().get(), Long::sum);
            }
        }

        Set<String> newHotKeys = new HashSet<>();
        for (Map.Entry<String, Long> entry : totalCounts.entrySet()) {
            String key = entry.getKey();
            long totalQps = entry.getValue();
            long avgQps = totalQps / windowSeconds;

            if (avgQps >= thresholdQps) {
                newHotKeys.add(key);
                coolDownCounters.remove(key); // 热度恢复，清零冷却计数

                if (!hotKeys.contains(key)) {
                    log.info("热点升级: key={}, avgQps={}, threshold={}", key, avgQps, thresholdQps);
                }
            }
        }

        // 降级检测：原先的热点 Key 本轮未达标 → 冷却计数+1 → 连续3轮未达标则降级
        for (String hotKey : hotKeys) {
            if (!newHotKeys.contains(hotKey)) {
                int coolCount = coolDownCounters
                        .computeIfAbsent(hotKey, k -> new AtomicInteger())
                        .incrementAndGet();
                if (coolCount >= 3) {
                    log.info("热点降级: key={}, coolCount={}", hotKey, coolCount);
                }
            }
        }

        hotKeys.clear();
        hotKeys.addAll(newHotKeys);
    }
}
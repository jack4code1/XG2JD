package com.seckill.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * 故障演练接口
 *
 * 面试可讲：
 * - 4个演练场景，每个场景记录注入时间+系统反应+恢复时间
 * - 通过 /actuator/metrics 实时观测指标变化
 * - 演练结束产出报告 → fault-drill-report.md
 */
@Slf4j
@RestController
@RequestMapping("/api/drill")
public class FaultDrillController {

    private String currentScenario = "none";
    private long injectTime = 0;

    /**
     * 标记故障开始
     */
    @PostMapping("/start")
    public Map<String, Object> startDrill(@RequestParam String scenario) {
        currentScenario = scenario;
        injectTime = Instant.now().toEpochMilli();
        log.warn("=== 故障演练开始: scenario={}, time={} ===", scenario, injectTime);
        return Map.of(
            "scenario", scenario,
            "injectTime", injectTime,
            "message", "请在终端执行: ./scripts/fault-inject.sh " + scenario
        );
    }

    /**
     * 标记故障恢复
     */
    @PostMapping("/recover")
    public Map<String, Object> recoverDrill() {
        long recoverTime = Instant.now().toEpochMilli();
        long durationMs = recoverTime - injectTime;

        log.warn("=== 故障演练恢复: scenario={}, duration={}ms ===",
                currentScenario, durationMs);

        String result = "unknown";
        if (currentScenario.equals("redis") && durationMs > 0) {
            result = durationMs < 5000 ? "good" : "slow";
        }

        Map<String, Object> resp = Map.of(
            "scenario", currentScenario,
            "recoverTime", recoverTime,
            "durationMs", durationMs,
            "result", result
        );
        currentScenario = "none";
        return resp;
    }

    /**
     * 查询当前演练状态
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
            "scenario", currentScenario,
            "injectTime", injectTime,
            "elapsedMs", currentScenario.equals("none") ? 0 :
                    Instant.now().toEpochMilli() - injectTime
        );
    }
}
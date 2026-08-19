package com.seckill.controller;

import com.seckill.agent.AgentOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI Agent API — Multi-Agent 运营团队入口
 *
 * 面试可讲：
 * - POST /api/ai/campaign/plan → 4个Agent并行策划活动
 * - 商家自然语言输入 → AI自动分析+推荐+生成文案
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiAgentController {

    private final AgentOrchestrator orchestrator;

    /**
     * AI 策划活动
     */
    @PostMapping("/campaign/plan")
    public Map<String, Object> planCampaign(@RequestBody Map<String, String> request) {
        String query = request.getOrDefault("query", "帮我策划一个秒杀活动");
        String plan = orchestrator.planCampaign(query);

        return Map.of(
            "success", true,
            "query", query,
            "plan", plan,
            "agents", new String[]{"📊数据分析Agent", "🛡️风控Agent", "✍️内容Agent", "📈策略Agent"}
        );
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "ok",
            "model", "DeepSeek Chat",
            "agents", new String[]{"data", "risk", "content", "strategy"}
        );
    }
}
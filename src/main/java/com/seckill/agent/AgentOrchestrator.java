package com.seckill.agent;

import com.seckill.agent.tools.DataAnalysisTools;
import com.seckill.agent.tools.RiskAnalysisTools;
import com.seckill.agent.tools.StrategyTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 🎯 Multi-Agent 编排器
 * 4个Agent并行 + 结果汇总
 */
@Slf4j
@Service
public class AgentOrchestrator {

    private final ChatClient dataAgent;
    private final ChatClient riskAgent;
    private final ChatClient contentAgent;
    private final ChatClient strategyAgent;
    private final DataAnalysisTools dataTools;
    private final RiskAnalysisTools riskTools;
    private final StrategyTools strategyTools;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public AgentOrchestrator(@Qualifier("dataAgent") ChatClient dataAgent,
                              @Qualifier("riskAgent") ChatClient riskAgent,
                              @Qualifier("contentAgent") ChatClient contentAgent,
                              @Qualifier("strategyAgent") ChatClient strategyAgent,
                              DataAnalysisTools dataTools,
                              RiskAnalysisTools riskTools,
                              StrategyTools strategyTools) {
        this.dataAgent = dataAgent;
        this.riskAgent = riskAgent;
        this.contentAgent = contentAgent;
        this.strategyAgent = strategyAgent;
        this.dataTools = dataTools;
        this.riskTools = riskTools;
        this.strategyTools = strategyTools;
    }

    /**
     * Copilot 主流程：先取真实业务快照，再并行调用 Agent，最后只返回结构化、可执行的结果。
     */
    public Map<String, Object> copilot(String query) {
        return copilot(query, null);
    }

    public Map<String, Object> copilot(String query, Long merchantId) {
        long start = System.currentTimeMillis();
        String request = query == null || query.isBlank() ? "分析当前秒杀运营情况" : query.trim();
        Map<String, Object> metrics = dataTools.snapshot(merchantId);
        Map<String, Object> risks = riskTools.snapshot();
        Map<String, Object> strategy = strategyTools.recommend(request);
        String context = "真实业务快照(不要编造数据): metrics=" + metrics + ", risks=" + risks + ", strategy=" + strategy;

        CompletableFuture<String> data = asyncCall(dataAgent,
                context + "\n用户问题: " + request + "\n请输出3条基于数据的发现和1条建议。",
                localDataFallback(metrics));
        CompletableFuture<String> risk = asyncCall(riskAgent,
                context + "\n用户问题: " + request + "\n请输出风险等级、证据和处置建议。",
                localRiskFallback(risks));
        CompletableFuture<String> content = asyncCall(contentAgent,
                context + "\n用户问题: " + request + "\n请给出一条可直接使用的活动标题和短描述。",
                localContentFallback(request));
        CompletableFuture<String> strategyAgentResult = asyncCall(strategyAgent,
                context + "\n用户问题: " + request + "\n请解释推荐库存、时长和限购参数的原因。",
                localStrategyFallback(strategy));
        CompletableFuture.allOf(data, risk, content, strategyAgentResult).join();

        String intent = detectIntent(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intent", intent);
        result.put("query", request);
        result.put("metrics", metrics);
        result.put("risks", risks);
        result.put("recommendation", strategy);
        result.put("agents", Map.of(
                "data", data.join(),
                "risk", risk.join(),
                "content", content.join(),
                "strategy", strategyAgentResult.join()));
        result.put("actions", intent.equals("CAMPAIGN")
                ? List.of(Map.of("id", "CREATE_COUPON", "label", "按推荐参数创建优惠券", "requiresConfirmation", true))
                : List.of(Map.of("id", "REFRESH_INSIGHTS", "label", "刷新实时数据", "requiresConfirmation", false)));
        result.put("requiresConfirmation", intent.equals("CAMPAIGN"));
        result.put("elapsedMs", System.currentTimeMillis() - start);
        result.put("degraded", data.join().startsWith("[本地降级]") || risk.join().startsWith("[本地降级]")
                || content.join().startsWith("[本地降级]") || strategyAgentResult.join().startsWith("[本地降级]"));
        return result;
    }

    private String localDataFallback(Map<String, Object> metrics) {
        return "[本地降级] 基于实时数据库快照：近7天订单 " + metrics.getOrDefault("orders7d", 0)
                + "，支付转化率 " + metrics.getOrDefault("paymentRate", 0) + "%，库存售罄率 "
                + metrics.getOrDefault("sellThroughRate", 0) + "%。建议先观察支付链路，再扩大活动库存。";
    }

    private String localRiskFallback(Map<String, Object> risks) {
        return "[本地降级] 当前风险等级：" + risks.getOrDefault("riskLevel", "UNKNOWN")
                + "；黑名单设备：" + risks.getOrDefault("blacklistedDevices", 0)
                + "。已启用设备指纹、滑动窗口、一人一单和 Sentinel 限流。";
    }

    private String localContentFallback(String query) {
        return "[本地降级] 优惠券名称: " + (query.contains("新用户") ? "新客首单惊喜券" : "限时秒杀惊喜券")
                + "\n优惠券描述: 限量发放，先到先得，活动期间每位用户限领指定数量。";
    }

    private String localStrategyFallback(Map<String, Object> strategy) {
        return "[本地降级] 推荐库存 " + strategy.getOrDefault("stock", 800) + "，有效时长 "
                + strategy.getOrDefault("durationHours", 24) + " 小时，每人限领 "
                + strategy.getOrDefault("perUserMax", 1) + " 张。";
    }

    private String detectIntent(String query) {
        if (query.contains("创建") || query.contains("策划") || query.contains("活动") || query.contains("优惠券")) {
            return "CAMPAIGN";
        }
        if (query.contains("风险") || query.contains("攻击") || query.contains("黄牛") || query.contains("风控")) {
            return "RISK";
        }
        if (query.contains("数据") || query.contains("订单") || query.contains("库存") || query.contains("分析")) {
            return "ANALYSIS";
        }
        return "ASSIST";
    }

    public String planCampaign(String userRequest) {
        long start = System.currentTimeMillis();
        log.info("Orchestrator: {}", userRequest);

        CompletableFuture<String> dataF = asyncCall(dataAgent,
                "分析当前系统数据: 订单数、活跃用户、活动完成率");

        CompletableFuture<String> riskF = asyncCall(riskAgent,
                "评估当前安全风险, 给出风控建议");

        CompletableFuture<String> contentF = asyncCall(contentAgent,
                "为「" + userRequest + "」生成优惠券名称和描述");

        CompletableFuture<String> strategyF = asyncCall(strategyAgent,
                "为「" + userRequest + "」推荐最优策略(库存、限购、时长、权重)");

        CompletableFuture.allOf(dataF, riskF, contentF, strategyF).join();

        long elapsed = System.currentTimeMillis() - start;
        String plan = String.format("""
                🤖 AI运营团队 · 活动策划方案

                📊 数据分析
                %s

                🛡️ 风控评估
                %s

                ✍️ 内容策划
                %s

                📈 策略推荐
                %s

                ⚡ 4个Agent并行, 耗时%dms""",
                dataF.join(), riskF.join(), contentF.join(), strategyF.join(), elapsed);

        log.info("完成 {}ms", elapsed);
        return plan;
    }

    private CompletableFuture<String> asyncCall(ChatClient agent, String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return agent.prompt().user(prompt).call().content();
            } catch (Exception e) {
                log.error("Agent失败: {}", e.getMessage());
                return "Agent暂不可用: " + e.getMessage();
            }
        }, executor);
    }

    private CompletableFuture<String> asyncCall(ChatClient agent, String prompt, String fallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return agent.prompt().user(prompt).call().content();
            } catch (Exception e) {
                log.warn("Agent 调用失败，切换本地降级: {}", e.getClass().getSimpleName());
                return fallback;
            }
        }, executor);
    }
}

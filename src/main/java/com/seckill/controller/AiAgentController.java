package com.seckill.controller;

import com.seckill.common.Result;
import com.seckill.agent.AgentOrchestrator;
import com.seckill.repository.CouponRepository;
import com.seckill.repository.MerchantRepository;
import com.seckill.repository.AiAuditLogRepository;
import com.seckill.model.AiAuditLog;
import com.seckill.service.AiExecutionService;
import com.seckill.exception.ForbiddenException;
import com.seckill.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.List;

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
    private final CouponRepository couponRepository;
    private final MerchantRepository merchantRepository;
    private final AiAuditLogRepository aiAuditLogRepository;
    private final AiExecutionService aiExecutionService;

    /**
     * 将自然语言运营目标固化成不可变 Proposal。此接口只规划，不执行写操作。
     */
    @PostMapping("/tasks")
    public Result<Map<String, Object>> createTask(@RequestBody Map<String, String> request) {
        requireMerchant();
        return Result.ok(aiExecutionService.createTask(currentMerchantId(), request.get("query")));
    }

    /** 查询当前商户最近 20 个 AI 执行任务及动作时间线。 */
    @GetMapping("/tasks")
    public Result<List<Map<String, Object>>> tasks() {
        requireMerchant();
        return Result.ok(aiExecutionService.listTasks(currentMerchantId()));
    }

    /** 确认并执行已经保存的 Proposal，不再重新调用模型生成参数。 */
    @PostMapping("/tasks/{taskNo}/confirm")
    public Result<Map<String, Object>> confirmTask(@PathVariable String taskNo) {
        requireMerchant();
        return Result.ok(aiExecutionService.confirm(currentMerchantId(), taskNo));
    }

    @PostMapping("/tasks/{taskNo}/cancel")
    public Result<Map<String, Object>> cancelTask(@PathVariable String taskNo) {
        requireMerchant();
        return Result.ok(aiExecutionService.cancel(currentMerchantId(), taskNo));
    }

    /**
     * AI 策划活动
     */
    @PostMapping("/campaign/plan")
    public Result<Map<String, Object>> planCampaign(@RequestBody Map<String, String> request) {
        requireMerchant();
        String query = request.getOrDefault("query", "帮我策划一个秒杀活动");
        String plan = orchestrator.planCampaign(query);

        return Result.ok(Map.of(
            "query", query,
            "plan", plan,
            "agents", new String[]{"📊数据分析Agent", "🛡️风控Agent", "✍️内容Agent", "📈策略Agent"}
        ));
    }

    /**
     * AI 运营 Copilot：实时数据快照 → 意图识别 → Agent 并行分析 → 结构化动作。
     */
    @PostMapping("/copilot/query")
    public Result<Map<String, Object>> copilot(@RequestBody Map<String, String> request) {
        requireMerchant();
        Long merchantId = currentMerchantId();
        Map<String, Object> result = orchestrator.copilot(request.get("query"), merchantId);
        saveAudit(merchantId, result);
        return Result.ok(result);
    }

    /** 返回最近 20 次 Copilot 调用，供商家复盘 AI 推荐质量和降级情况。 */
    @GetMapping("/audits")
    public Result<java.util.List<AiAuditLog>> audits() {
        requireMerchant();
        return Result.ok(aiAuditLogRepository.findTop20ByMerchantIdOrderByCreatedAtDesc(currentMerchantId()));
    }

    /** 固定问题集的轻量评测，验证意图识别、结构化输出和降级可用性。 */
    @PostMapping("/eval")
    public Result<Map<String, Object>> eval() {
        requireMerchant();
        Long merchantId = currentMerchantId();
        List<Map<String, String>> cases = List.of(
                Map.of("query", "分析最近7天经营情况", "intent", "ANALYSIS"),
                Map.of("query", "检查当前活动的风控风险", "intent", "RISK"),
                Map.of("query", "帮我策划一个新客优惠券活动", "intent", "CAMPAIGN"),
                Map.of("query", "优惠券怎么使用", "intent", "ASSIST")
        );
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        for (Map<String, String> testCase : cases) {
            Map<String, Object> result = orchestrator.copilot(testCase.get("query"), merchantId);
            saveAudit(merchantId, result);
            boolean structured = result.containsKey("metrics") && result.containsKey("agents") && result.containsKey("actions");
            results.add(Map.of(
                    "query", testCase.get("query"),
                    "expectedIntent", testCase.get("intent"),
                    "actualIntent", result.get("intent"),
                    "intentPass", testCase.get("intent").equals(result.get("intent")),
                    "structuredPass", structured,
                    "degraded", result.get("degraded"),
                    "elapsedMs", result.get("elapsedMs")));
        }
        long passed = results.stream().filter(r -> Boolean.TRUE.equals(r.get("intentPass")) && Boolean.TRUE.equals(r.get("structuredPass"))).count();
        return Result.ok(Map.of("total", results.size(), "passed", passed, "passRate", passed * 100.0 / results.size(), "cases", results));
    }

    /**
     * Legacy route retained for clients that used copilot/execute. It now only
     * creates a pending task; direct model-triggered writes are forbidden.
     */
    @PostMapping("/copilot/execute")
    public Result<Map<String, Object>> executeCopilot(@RequestBody Map<String, String> request) {
        requireMerchant();
        return Result.ok(aiExecutionService.createTask(currentMerchantId(), request.get("query")));
    }

    /** Legacy route: create a reviewable task instead of one-click execution. */
    @PostMapping("/campaign/execute")
    public Result<Map<String, Object>> executePlan(@RequestBody Map<String, String> request) {
        requireMerchant();
        return Result.ok(aiExecutionService.createTask(currentMerchantId(),
                request.getOrDefault("query", "帮我创建一个限时优惠活动")));
    }

    private void requireMerchant() {
        if (!"MERCHANT".equals(UserContext.getRole())) {
            throw new ForbiddenException("只有商家可以使用 AI 运营功能");
        }
    }

    private Long currentMerchantId() {
        return merchantRepository.findByUserId(UserContext.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("商家店铺不存在")).getId();
    }

    private void saveAudit(Long merchantId, Map<String, Object> result) {
        aiAuditLogRepository.save(AiAuditLog.builder()
                .merchantId(merchantId)
                .query(String.valueOf(result.getOrDefault("query", "")))
                .intent(String.valueOf(result.getOrDefault("intent", "UNKNOWN")))
                .elapsedMs(((Number) result.getOrDefault("elapsedMs", 0L)).longValue())
                .degraded(Boolean.TRUE.equals(result.get("degraded")))
                .build());
    }

    private String extract(String plan, String keyword, String defaultValue) {
        for (String line : plan.split("\n")) {
            if (line.contains(keyword) && line.contains(":")) {
                return line.split("[:：]")[1].trim().replaceAll("[*🔥⚡]", "").trim();
            }
        }
        return defaultValue;
    }

    private int extractInt(String plan, int defaultValue) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("库存[^0-9]*([0-9]+)");
        java.util.regex.Matcher m = p.matcher(plan.replaceAll("\\s", ""));
        if (m.find()) { try { return Integer.parseInt(m.group(1)); } catch (Exception e) {} }
        return defaultValue;
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of(
            "status", "ok",
            "model", "DeepSeek Chat",
            "agents", new String[]{"data", "risk", "content", "strategy"},
            "executionTools", new String[]{"CREATE_CAMPAIGN", "INCREASE_STOCK", "PAUSE_CAMPAIGN", "RESUME_CAMPAIGN"}
        ));
    }
}

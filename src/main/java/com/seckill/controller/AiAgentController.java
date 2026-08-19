package com.seckill.controller;

import com.seckill.agent.AgentOrchestrator;
import com.seckill.repository.CouponRepository;
import com.seckill.repository.MerchantRepository;
import com.seckill.exception.ForbiddenException;
import com.seckill.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
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
    private final CouponRepository couponRepository;
    private final MerchantRepository merchantRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * AI 策划活动
     */
    @PostMapping("/campaign/plan")
    public Map<String, Object> planCampaign(@RequestBody Map<String, String> request) {
        requireMerchant();
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
     * AI 运营 Copilot：实时数据快照 → 意图识别 → Agent 并行分析 → 结构化动作。
     */
    @PostMapping("/copilot/query")
    public Map<String, Object> copilot(@RequestBody Map<String, String> request) {
        requireMerchant();
        return orchestrator.copilot(request.get("query"), currentMerchantId());
    }

    /** 写操作必须由商家二次确认后调用，避免模型直接修改业务数据。 */
    @PostMapping("/copilot/execute")
    public Map<String, Object> executeCopilot(@RequestBody Map<String, String> request) {
        requireMerchant();
        Map<String, Object> result = orchestrator.copilot(request.get("query"), currentMerchantId());
        if (!"CAMPAIGN".equals(result.get("intent"))) {
            throw new IllegalArgumentException("当前对话没有可执行的活动创建动作");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> recommendation = (Map<String, Object>) result.get("recommendation");
        @SuppressWarnings("unchecked")
        Map<String, String> agents = (Map<String, String>) result.get("agents");
        int stock = ((Number) recommendation.getOrDefault("stock", 800)).intValue();
        int hours = ((Number) recommendation.getOrDefault("durationHours", 24)).intValue();
        int perUserMax = ((Number) recommendation.getOrDefault("perUserMax", 1)).intValue();
        String content = agents.getOrDefault("content", "限时秒杀券");
        String couponName = extract(content, "优惠券名称", "限时秒杀券");

        var now = java.time.LocalDateTime.now();
        var coupon = new com.seckill.model.Coupon();
        coupon.setMerchantId(merchantRepository.findByUserId(UserContext.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("商家店铺不存在")).getId());
        coupon.setCouponName(couponName);
        coupon.setCouponDesc(content);
        coupon.setTotalStock(stock);
        coupon.setRemainStock(stock);
        coupon.setStartTime(now);
        coupon.setEndTime(now.plusHours(hours));
        coupon.setPerUserMax(perUserMax);
        coupon.setStatus(1);
        couponRepository.save(coupon);
        warmup(coupon);

        return Map.of("success", true, "couponId", coupon.getId(), "couponName", couponName,
                "stock", stock, "durationHours", hours, "copilot", result);
    }

    /**
     * AI一键执行：分析方案 → 自动创建活动
     */
    @PostMapping("/campaign/execute")
    public Map<String, Object> executePlan(@RequestBody Map<String, String> request) {
        requireMerchant();
        String query = request.getOrDefault("query", "帮我策划一个秒杀活动");
        // 先用 AI 出方案
        String plan = orchestrator.planCampaign(query);

        // 从方案中提取参数 → 自动创建
        try {
            var now = new java.util.Date();
            var end = new java.util.Date(now.getTime() + 4 * 3600000);
            var coupon = new com.seckill.model.Coupon();
            coupon.setMerchantId(merchantRepository.findByUserId(UserContext.getUserId())
                    .orElseThrow(() -> new IllegalArgumentException("商家店铺不存在")).getId());
            coupon.setCouponName(extract(plan, "名称", query.contains("双11") ? "双11秒杀券" : "秒杀券"));
            coupon.setTotalStock(extractInt(plan, 500));
            coupon.setRemainStock(coupon.getTotalStock());
            coupon.setStartTime(new java.sql.Timestamp(now.getTime()).toLocalDateTime());
            coupon.setEndTime(new java.sql.Timestamp(end.getTime()).toLocalDateTime());
            coupon.setPerUserMax(1);
            coupon.setStatus(1);
            couponRepository.save(coupon);

            // Redis 预热
            String key = "seckill:coupon:" + coupon.getId();
            Map<String, Object> fields = new java.util.HashMap<>();
            fields.put("total", coupon.getTotalStock());
            fields.put("remain", coupon.getRemainStock());
            fields.put("version", 0);
            fields.put("per_user_max", 1);
            fields.put("status", 1);
            fields.put("start_time", String.valueOf(coupon.getStartTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()));
            fields.put("end_time", String.valueOf(coupon.getEndTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()));
            redisTemplate.opsForHash().putAll(key, fields);

            return Map.of("success", true, "couponId", coupon.getId(), "couponName", coupon.getCouponName(), "plan", plan);
        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage(), "plan", plan);
        }
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

    private String extract(String plan, String keyword, String defaultValue) {
        for (String line : plan.split("\n")) {
            if (line.contains(keyword) && line.contains(":")) {
                return line.split("[:：]")[1].trim().replaceAll("[*🔥⚡]", "").trim();
            }
        }
        return defaultValue;
    }

    private void warmup(com.seckill.model.Coupon coupon) {
        String key = "seckill:coupon:" + coupon.getId();
        Map<String, Object> fields = new java.util.HashMap<>();
        fields.put("total", coupon.getTotalStock());
        fields.put("remain", coupon.getRemainStock());
        fields.put("version", 0);
        fields.put("per_user_max", coupon.getPerUserMax());
        fields.put("status", 1);
        fields.put("start_time", String.valueOf(coupon.getStartTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()));
        fields.put("end_time", String.valueOf(coupon.getEndTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()));
        redisTemplate.opsForHash().putAll(key, fields);
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
    public Map<String, Object> health() {
        return Map.of(
            "status", "ok",
            "model", "DeepSeek Chat",
            "agents", new String[]{"data", "risk", "content", "strategy"}
        );
    }
}

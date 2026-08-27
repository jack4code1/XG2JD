package com.seckill.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.agent.AgentOrchestrator;
import com.seckill.model.AiAction;
import com.seckill.model.AiTask;
import com.seckill.model.Coupon;
import com.seckill.repository.AiActionRepository;
import com.seckill.repository.AiTaskRepository;
import com.seckill.repository.CouponRepository;
import com.seckill.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 运营执行服务。模型只负责把业务目标转换成受约束 Proposal；真正的写操作
 * 只能通过白名单工具执行，并且高风险动作必须确认 taskNo 后才能发生。
 */
@Service
@RequiredArgsConstructor
public class AiExecutionService {

    public static final String WAITING_CONFIRMATION = "WAITING_CONFIRMATION";
    public static final String EXECUTING = "EXECUTING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELED = "CANCELED";

    public static final String CREATE_CAMPAIGN = "CREATE_CAMPAIGN";
    public static final String INCREASE_STOCK = "INCREASE_STOCK";
    public static final String PAUSE_CAMPAIGN = "PAUSE_CAMPAIGN";
    public static final String RESUME_CAMPAIGN = "RESUME_CAMPAIGN";

    private final AiTaskRepository taskRepository;
    private final AiActionRepository actionRepository;
    private final CouponRepository couponRepository;
    private final CouponCacheService couponCacheService;
    private final CouponVersionService couponVersionService;
    private final NotificationService notificationService;
    private final MerchantRepository merchantRepository;
    private final CouponSeckillStateService couponSeckillStateService;
    private final AgentOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public Map<String, Object> createTask(Long merchantId, String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException("请输入希望 AI 执行的运营任务");

        String actionType = detectAction(normalized);
        Map<String, Object> proposal = switch (actionType) {
            case CREATE_CAMPAIGN -> planCampaign(merchantId, normalized);
            case INCREASE_STOCK -> planStockIncrease(merchantId, normalized);
            case PAUSE_CAMPAIGN -> planStatusChange(merchantId, normalized, true);
            case RESUME_CAMPAIGN -> planStatusChange(merchantId, normalized, false);
            default -> throw new IllegalArgumentException("当前支持：创建活动、追加库存、暂停活动、恢复活动");
        };

        Long targetCouponId = proposal.get("couponId") instanceof Number n ? n.longValue() : null;
        AiTask task = taskRepository.save(AiTask.builder()
                .taskNo(UUID.randomUUID().toString())
                .merchantId(merchantId)
                .query(normalized)
                .actionType(actionType)
                .status(WAITING_CONFIRMATION)
                .targetCouponId(targetCouponId)
                .proposalJson(writeJson(proposal))
                .requiresConfirmation(true)
                .build());

        actionRepository.save(AiAction.builder()
                .taskId(task.getId())
                .merchantId(merchantId)
                .actionType(actionType)
                .status(WAITING_CONFIRMATION)
                .inputJson(task.getProposalJson())
                .build());
        return toView(task);
    }

    public List<Map<String, Object>> listTasks(Long merchantId) {
        return taskRepository.findTop20ByMerchantIdOrderByCreatedAtDesc(merchantId)
                .stream().map(this::toView).toList();
    }

    public Map<String, Object> confirm(Long merchantId, String taskNo) {
        AiTask task = ownedTask(merchantId, taskNo);
        if (COMPLETED.equals(task.getStatus())) return toView(task); // confirmation is idempotent
        if (!WAITING_CONFIRMATION.equals(task.getStatus())) {
            throw new IllegalArgumentException("任务当前状态不可执行：" + task.getStatus());
        }

        LocalDateTime confirmedAt = LocalDateTime.now();
        if (taskRepository.claimWaitingForExecution(taskNo, merchantId, WAITING_CONFIRMATION, EXECUTING, confirmedAt) != 1) {
            AiTask latest = ownedTask(merchantId, taskNo);
            if (COMPLETED.equals(latest.getStatus())) return toView(latest);
            if (EXECUTING.equals(latest.getStatus())) {
                throw new IllegalStateException("任务正在由另一实例执行，请稍后刷新结果");
            }
            throw new IllegalArgumentException("任务当前状态不可执行：" + latest.getStatus());
        }

        // Reload after the conditional update so this instance works on the
        // state it successfully claimed, not a stale entity snapshot.
        task = ownedTask(merchantId, taskNo);
        AiAction action = actionRepository.findByTaskIdOrderByCreatedAtAsc(task.getId()).stream()
                .findFirst().orElseThrow(() -> new IllegalStateException("任务缺少动作记录"));
        action.setStatus(EXECUTING);
        actionRepository.save(action);

        try {
            Map<String, Object> proposal = readJson(task.getProposalJson());
            Map<String, Object> result = switch (task.getActionType()) {
                case CREATE_CAMPAIGN -> executeCreate(task, proposal);
                case INCREASE_STOCK -> executeStockIncrease(task, proposal);
                case PAUSE_CAMPAIGN -> executeStatusChange(task, true);
                case RESUME_CAMPAIGN -> executeStatusChange(task, false);
                default -> throw new IllegalArgumentException("不允许执行未知工具：" + task.getActionType());
            };
            task.setStatus(COMPLETED);
            task.setResultJson(writeJson(result));
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
            action.setStatus(COMPLETED);
            action.setResultJson(task.getResultJson());
            action.setExecutedAt(LocalDateTime.now());
            actionRepository.save(action);
            notifyMerchant(task.getMerchantId(), "AI_TASK_COMPLETED", "AI 活动任务已执行",
                    "「" + task.getActionType() + "」已完成，可在活动管理中查看版本和缓存快照。");
            return toView(task);
        } catch (Exception e) {
            task.setStatus(FAILED);
            task.setResultJson(writeJson(Map.of("success", false, "message", safeMessage(e))));
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
            action.setStatus(FAILED);
            action.setErrorMessage(safeMessage(e));
            action.setExecutedAt(LocalDateTime.now());
            actionRepository.save(action);
            notifyMerchant(task.getMerchantId(), "AI_TASK_FAILED", "AI 活动任务执行失败",
                    "「" + task.getActionType() + "」执行失败：" + safeMessage(e));
            return toView(task);
        }
    }

    public Map<String, Object> cancel(Long merchantId, String taskNo) {
        AiTask task = ownedTask(merchantId, taskNo);
        if (!WAITING_CONFIRMATION.equals(task.getStatus()) && !"RECOVERY_REQUIRED".equals(task.getStatus())) {
            throw new IllegalArgumentException("只有待确认或待人工复核任务可以取消");
        }
        task.setStatus(CANCELED);
        task.setCompletedAt(LocalDateTime.now());
        taskRepository.save(task);
        actionRepository.findByTaskIdOrderByCreatedAtAsc(task.getId()).forEach(action -> {
            action.setStatus(CANCELED);
            action.setExecutedAt(LocalDateTime.now());
            actionRepository.save(action);
        });
        return toView(task);
    }

    private Map<String, Object> planCampaign(Long merchantId, String query) {
        String modelQuery = query.replace("新客", "新用户");
        Map<String, Object> copilot = orchestrator.copilot(modelQuery, merchantId);
        @SuppressWarnings("unchecked")
        Map<String, Object> recommendation = (Map<String, Object>) copilot.getOrDefault("recommendation", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, String> agents = (Map<String, String>) copilot.getOrDefault("agents", Map.of());
        int stock = bounded(number(recommendation.get("stock"), query.contains("新客") ? 500 : 800), 1, 5000);
        int hours = bounded(number(recommendation.get("durationHours"), 24), 1, 168);
        int perUserMax = bounded(number(recommendation.get("perUserMax"), 1), 1, 5);
        BigDecimal discount = query.contains("50") ? new BigDecimal("50")
                : query.contains("新客") ? new BigDecimal("20") : new BigDecimal("15");
        String content = agents.getOrDefault("content", "");
        String name = extractLine(content, "优惠券名称", query.contains("新客") ? "新客限时券" : "AI 运营秒杀券");

        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("tool", CREATE_CAMPAIGN);
        proposal.put("summary", "创建并发布优惠券，同时预热 Redis 秒杀库存");
        proposal.put("couponName", name);
        proposal.put("couponDesc", content.isBlank() ? query : content);
        proposal.put("discountAmount", discount);
        proposal.put("stock", stock);
        proposal.put("durationHours", hours);
        proposal.put("perUserMax", perUserMax);
        proposal.put("requiresConfirmation", true);
        proposal.put("degraded", Boolean.TRUE.equals(copilot.get("degraded")));
        proposal.put("guardrails", List.of("库存 1-5000", "有效期 1-168 小时", "每人限领 1-5 张", "仅当前商户可确认"));
        return proposal;
    }

    private Map<String, Object> planStockIncrease(Long merchantId, String query) {
        Long couponId = extractCouponId(query);
        int amount = extractAmount(query);
        Coupon coupon = ownedCoupon(merchantId, couponId);
        if (amount < 1 || amount > 5000) throw new IllegalArgumentException("单次追加库存必须在 1-5000 张之间");
        return linkedMap(
                "tool", INCREASE_STOCK,
                "summary", "为「" + coupon.getCouponName() + "」追加 " + amount + " 张库存",
                "couponId", couponId,
                "couponName", coupon.getCouponName(),
                "amount", amount,
                "currentTotal", coupon.getTotalStock(),
                "currentRemain", coupon.getRemainStock(),
                "afterTotal", coupon.getTotalStock() + amount,
                "afterRemain", coupon.getRemainStock() + amount,
                "requiresConfirmation", true);
    }

    private Map<String, Object> planStatusChange(Long merchantId, String query, boolean pause) {
        Long couponId = extractCouponId(query);
        Coupon coupon = ownedCoupon(merchantId, couponId);
        return linkedMap(
                "tool", pause ? PAUSE_CAMPAIGN : RESUME_CAMPAIGN,
                "summary", (pause ? "暂停" : "恢复") + "「" + coupon.getCouponName() + "」",
                "couponId", couponId,
                "couponName", coupon.getCouponName(),
                "currentStatus", coupon.getStatus(),
                "targetStatus", pause ? 3 : 1,
                "requiresConfirmation", true);
    }

    private Map<String, Object> executeCreate(AiTask task, Map<String, Object> proposal) {
        Coupon coupon = new Coupon();
        coupon.setMerchantId(task.getMerchantId());
        coupon.setCouponName(String.valueOf(proposal.get("couponName")));
        coupon.setCouponDesc(String.valueOf(proposal.get("couponDesc")));
        coupon.setDiscountAmount(new BigDecimal(String.valueOf(proposal.get("discountAmount"))));
        int stock = number(proposal.get("stock"), 800);
        coupon.setTotalStock(stock);
        coupon.setRemainStock(stock);
        coupon.setStartTime(LocalDateTime.now());
        coupon.setEndTime(LocalDateTime.now().plusHours(number(proposal.get("durationHours"), 24)));
        coupon.setPerUserMax(number(proposal.get("perUserMax"), 1));
        coupon.setStatus(1);
        coupon = couponRepository.saveAndFlush(coupon);
        try {
            warmup(coupon);
        } catch (RuntimeException e) {
            couponSeckillStateService.clear(coupon);
            couponRepository.deleteById(coupon.getId());
            throw e;
        }
        task.setTargetCouponId(coupon.getId());
        couponVersionService.record(coupon, "AI_CREATE", null);
        return linkedMap("success", true, "tool", CREATE_CAMPAIGN, "couponId", coupon.getId(),
                "couponName", coupon.getCouponName(), "stock", stock, "redisWarmed", true);
    }

    private Map<String, Object> executeStockIncrease(AiTask task, Map<String, Object> proposal) {
        Coupon coupon = ownedCoupon(task.getMerchantId(), task.getTargetCouponId());
        int amount = bounded(number(proposal.get("amount"), 0), 1, 5000);
        int oldTotal = coupon.getTotalStock();
        int oldRemain = coupon.getRemainStock();
        coupon.setTotalStock(oldTotal + amount);
        coupon.setRemainStock(oldRemain + amount);
        coupon = couponRepository.saveAndFlush(coupon);
        try {
            syncMutableFields(coupon, true);
        } catch (RuntimeException e) {
            coupon.setTotalStock(oldTotal);
            coupon.setRemainStock(oldRemain);
            couponRepository.saveAndFlush(coupon);
            syncMutableFields(coupon, true);
            throw e;
        }
        couponVersionService.record(coupon, "AI_INCREASE_STOCK", null);
        return linkedMap("success", true, "tool", INCREASE_STOCK, "couponId", coupon.getId(),
                "added", amount, "totalStock", coupon.getTotalStock(), "remainStock", coupon.getRemainStock());
    }

    private Map<String, Object> executeStatusChange(AiTask task, boolean pause) {
        Coupon coupon = ownedCoupon(task.getMerchantId(), task.getTargetCouponId());
        int oldStatus = coupon.getStatus();
        LocalDateTime oldEnd = coupon.getEndTime();
        coupon.setStatus(pause ? 3 : 1);
        if (!pause && coupon.getEndTime().isBefore(LocalDateTime.now())) {
            coupon.setEndTime(LocalDateTime.now().plusHours(24));
        }
        coupon = couponRepository.saveAndFlush(coupon);
        try {
            syncMutableFields(coupon, false);
        } catch (RuntimeException e) {
            coupon.setStatus(oldStatus);
            coupon.setEndTime(oldEnd);
            couponRepository.saveAndFlush(coupon);
            syncMutableFields(coupon, false);
            throw e;
        }
        couponVersionService.record(coupon, pause ? "AI_PAUSE" : "AI_RESUME", null);
        return linkedMap("success", true, "tool", pause ? PAUSE_CAMPAIGN : RESUME_CAMPAIGN,
                "couponId", coupon.getId(), "couponName", coupon.getCouponName(), "status", coupon.getStatus());
    }

    private String detectAction(String query) {
        if (query.contains("暂停") || query.contains("停止") || query.contains("下线")) return PAUSE_CAMPAIGN;
        if (query.contains("恢复") || query.contains("继续活动") || query.contains("重新开启")) return RESUME_CAMPAIGN;
        if ((query.contains("追加") || query.contains("增加") || query.contains("补充")) && query.contains("库存")) return INCREASE_STOCK;
        if (query.contains("创建") || query.contains("新建") || query.contains("策划") || query.contains("活动") || query.contains("优惠券")) return CREATE_CAMPAIGN;
        throw new IllegalArgumentException("没有识别到可执行动作，请明确创建活动、追加库存、暂停或恢复优惠券");
    }

    private Long extractCouponId(String query) {
        Matcher explicit = Pattern.compile("(?:优惠券|活动|券)\\s*#?\\s*(\\d+)").matcher(query);
        if (explicit.find()) return Long.parseLong(explicit.group(1));
        throw new IllegalArgumentException("请在指令中写明优惠券 ID，例如：暂停优惠券 3");
    }

    private int extractAmount(String query) {
        Matcher matcher = Pattern.compile("(?:追加|增加|补充)\\s*(\\d+)").matcher(query);
        if (matcher.find()) return Integer.parseInt(matcher.group(1));
        throw new IllegalArgumentException("请写明追加数量，例如：给优惠券 3 追加 100 张库存");
    }

    private Coupon ownedCoupon(Long merchantId, Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("优惠券不存在：" + couponId));
        if (!merchantId.equals(coupon.getMerchantId())) throw new IllegalArgumentException("无权操作其他商户的优惠券");
        return coupon;
    }

    private AiTask ownedTask(Long merchantId, String taskNo) {
        return taskRepository.findByTaskNoAndMerchantId(taskNo, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在或不属于当前商户"));
    }

    private void warmup(Coupon coupon) {
        couponSeckillStateService.initialize(coupon);
        couponCacheService.publish(coupon);
    }

    private void syncMutableFields(Coupon coupon, boolean replaceStock) {
        couponSeckillStateService.syncActivity(coupon);
        if (replaceStock) couponSeckillStateService.replaceStock(coupon);
        couponCacheService.publish(coupon);
    }

    private Map<String, Object> toView(AiTask task) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskNo", task.getTaskNo());
        view.put("query", task.getQuery());
        view.put("actionType", task.getActionType());
        view.put("status", task.getStatus());
        view.put("targetCouponId", task.getTargetCouponId());
        view.put("requiresConfirmation", task.getRequiresConfirmation());
        view.put("proposal", readJson(task.getProposalJson()));
        view.put("result", task.getResultJson() == null ? null : readJson(task.getResultJson()));
        view.put("createdAt", task.getCreatedAt());
        view.put("confirmedAt", task.getConfirmedAt());
        view.put("completedAt", task.getCompletedAt());
        List<Map<String, Object>> actions = actionRepository.findByTaskIdOrderByCreatedAtAsc(task.getId()).stream().map(action -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("actionType", action.getActionType());
            row.put("status", action.getStatus());
            row.put("result", action.getResultJson() == null ? null : readJson(action.getResultJson()));
            row.put("errorMessage", action.getErrorMessage());
            row.put("createdAt", action.getCreatedAt());
            row.put("executedAt", action.getExecutedAt());
            return row;
        }).toList();
        view.put("actions", actions);
        return view;
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("任务参数序列化失败", e); }
    }

    private Map<String, Object> readJson(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalStateException("任务参数读取失败", e); }
    }

    private int number(Object value, int defaultValue) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return defaultValue; }
    }

    private int bounded(int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException("参数超出安全范围：" + value);
        return value;
    }

    private String extractLine(String text, String key, String fallback) {
        for (String line : text.split("\\R")) {
            if (line.contains(key) && (line.contains(":") || line.contains("："))) {
                String[] pair = line.split("[:：]", 2);
                if (pair.length == 2 && !pair[1].isBlank()) return pair[1].trim();
            }
        }
        return fallback;
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private void notifyMerchant(Long merchantId, String type, String title, String content) {
        merchantRepository.findById(merchantId)
                .ifPresent(merchant -> notificationService.notify(merchant.getUserId(), type, title, content));
    }

    private Map<String, Object> linkedMap(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) map.put(String.valueOf(values[i]), values[i + 1]);
        return map;
    }
}

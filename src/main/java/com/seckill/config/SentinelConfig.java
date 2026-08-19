package com.seckill.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 流控降级规则配置
 *
 * 面试可讲：
 * - 慢调用比例降级：RT>100ms 且比例>50% → 自动降级10s
 * - 异常比例降级：异常率>30% → 降级10s
 * - 流量控制：QPS>1000 → 限流
 * - 这些都是生产标配，阿里双11验证过的方案
 */
@Slf4j
@Configuration
public class SentinelConfig {

    /** 秒杀接口资源名 */
    public static final String SECKILL_RESOURCE = "seckill.execute";

    @PostConstruct
    public void initRules() {
        initDegradeRules();
        initFlowRules();
        log.info("Sentinel规则初始化完成");
    }

    /**
     * 降级规则
     */
    private void initDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();

        // 慢调用比例降级：RT > 100ms 的请求超过 50% → 降级 10s
        DegradeRule slowCallRule = new DegradeRule(SECKILL_RESOURCE)
                .setGrade(RuleConstant.DEGRADE_GRADE_RT)
                .setCount(100)        // 最大 RT 100ms
                .setTimeWindow(10)    // 降级窗口 10s
                .setMinRequestAmount(5)
                .setSlowRatioThreshold(0.5);
        rules.add(slowCallRule);

        // 异常比例降级：异常率 > 30% → 降级 10s
        DegradeRule exceptionRule = new DegradeRule(SECKILL_RESOURCE)
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.3)        // 异常比例阈值 30%
                .setTimeWindow(10);
        rules.add(exceptionRule);

        DegradeRuleManager.loadRules(rules);
    }

    /**
     * 流量控制规则
     */
    private void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();

        // QPS 超过 1000 → 限流
        FlowRule flowRule = new FlowRule(SECKILL_RESOURCE)
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(1000);
        rules.add(flowRule);

        FlowRuleManager.loadRules(rules);
    }
}
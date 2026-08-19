package com.seckill.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public AgentOrchestrator(@Qualifier("dataAgent") ChatClient dataAgent,
                              @Qualifier("riskAgent") ChatClient riskAgent,
                              @Qualifier("contentAgent") ChatClient contentAgent,
                              @Qualifier("strategyAgent") ChatClient strategyAgent) {
        this.dataAgent = dataAgent;
        this.riskAgent = riskAgent;
        this.contentAgent = contentAgent;
        this.strategyAgent = strategyAgent;
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
}
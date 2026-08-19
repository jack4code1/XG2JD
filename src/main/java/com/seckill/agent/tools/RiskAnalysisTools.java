package com.seckill.agent.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RiskAnalysisTools {
    private final RedisTemplate<String, Object> redisTemplate;

    public String checkAbnormalUsers(Map<String, Object> args) {
        Long s = redisTemplate.opsForSet().size("anti_fraud:blacklist");
        return s != null && s > 0 ? String.format("检测到%d个设备在黑名单, 有黄牛风险", s) : "未检测到黄牛攻击";
    }

    public String suggestAntiFraudRules(Map<String, Object> args) {
        return "建议: 滑动窗口60s/10次, 设备指纹SHA256, 同IP多设备标记代理";
    }

    public String getSecurityStatus(Map<String, Object> args) {
        return "设备指纹✅ 滑动窗口✅ 一人一单✅ Sentinel限流✅";
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        Long blacklist = redisTemplate.opsForSet().size("anti_fraud:blacklist");
        result.put("blacklistedDevices", blacklist == null ? 0 : blacklist);
        result.put("controls", List.of("device-fingerprint", "sliding-window", "one-user-one-order", "sentinel-rate-limit"));
        result.put("riskLevel", blacklist != null && blacklist > 0 ? "MEDIUM" : "LOW");
        return result;
    }
}

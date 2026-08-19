package com.seckill.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.LinkedHashMap;

@Slf4j
@Component
public class StrategyTools {

    public String suggestStock(Map<String, Object> args) {
        String type = getStr(args, "campaignType", "日常活动");
        return "建议「" + type + "」库存: 500张（参考历史完成率91%）";
    }

    public String suggestTimeWindow(Map<String, Object> args) {
        String type = getStr(args, "campaignType", "日常");
        return switch (type) {
            case "双11" -> "建议时长: 2小时（短时高频）";
            case "新用户专享" -> "建议时长: 72小时（充足发现时间）";
            default -> "建议时长: 4小时（平衡紧迫感和覆盖面）";
        };
    }

    public String suggestAllocationStrategy(Map<String, Object> args) {
        return "新用户+50权重, 沉睡用户+30权重, 防黄牛滑动窗60s/10次, 基础权重100";
    }

    public Map<String, Object> recommend(String query) {
        String normalized = query == null ? "" : query;
        Map<String, Object> result = new LinkedHashMap<>();
        if (normalized.contains("双11") || normalized.contains("大促")) {
            result.put("stock", 3000);
            result.put("durationHours", 4);
            result.put("perUserMax", 1);
        } else if (normalized.contains("新用户")) {
            result.put("stock", 500);
            result.put("durationHours", 72);
            result.put("perUserMax", 2);
        } else {
            result.put("stock", 800);
            result.put("durationHours", 24);
            result.put("perUserMax", 1);
        }
        result.put("allocation", "基础权重100；新用户+50；沉睡用户+30；风控滑动窗口60秒/10次");
        return result;
    }

    private String getStr(Map<String, Object> args, String key, String def) {
        Object v = args.get(key);
        return v != null ? v.toString() : def;
    }
}

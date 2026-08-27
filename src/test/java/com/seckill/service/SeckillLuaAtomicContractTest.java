package com.seckill.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SeckillLuaAtomicContractTest {

    @Test
    void qualificationScriptMustCommitUserAndStockInOneScript() throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream("lua/check_qualify.lua")) {
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int decrement = script.indexOf("redis.call('DECR', stockKey)");
            int markUser = script.indexOf("SADD', userSetKey, userId");

            assertTrue(decrement >= 0, "秒杀 Lua 必须原子扣减库存");
            assertTrue(markUser >= 0, "秒杀 Lua 必须原子标记用户");
            assertTrue(decrement < markUser, "扣库存和标记用户必须处于同一脚本提交路径");
        }
    }

    @Test
    void qualificationScriptMustRejectPausedCampaignsBeforeChangingStock() throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream("lua/check_qualify.lua")) {
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int statusCheck = script.indexOf("if status == '3' then return -3 end");
            int pausedReturn = script.indexOf("return -3");
            int decrement = script.indexOf("redis.call('DECR', stockKey)");

            assertTrue(statusCheck >= 0, "秒杀 Lua 必须校验活动状态");
            assertTrue(pausedReturn > statusCheck, "暂停活动必须返回独立错误码");
            assertTrue(pausedReturn < decrement, "活动状态必须在扣库存之前校验");
        }
    }

    @Test
    void qualificationScriptMustExposeDistinctLifecycleAndPendingContracts() throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream("lua/check_qualify.lua")) {
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(script.contains("seckill:activity:{couponId}"));
            assertTrue(script.contains("seckill:stock:{couponId}"));
            assertTrue(script.contains("seckill:users:{couponId}"));
            assertTrue(script.contains("seckill:pending:{couponId}"));
            assertTrue(script.contains("redis.call('RPUSH', pendingActivityKey, orderNo)"));
            assertTrue(script.contains("if status == '0' then return -1 end"));
            assertTrue(script.contains("if status == '2' then return -2 end"));
        }
    }
}

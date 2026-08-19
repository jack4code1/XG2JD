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
            int decrement = script.indexOf("HINCRBY', couponKey, 'remain', -1");
            int markUser = script.indexOf("SADD', userSetKey, userId");

            assertTrue(decrement >= 0, "秒杀 Lua 必须原子扣减库存");
            assertTrue(markUser >= 0, "秒杀 Lua 必须原子标记用户");
            assertTrue(decrement < markUser, "扣库存和标记用户必须处于同一脚本提交路径");
        }
    }
}

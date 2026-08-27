package com.seckill.service;

import com.seckill.cache.HotKeyDetector;
import com.seckill.dto.SeckillResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeckillServiceTest {
    private RedisTemplate<String, Object> redis;
    private DefaultRedisScript<Long> script;
    private AllocationService allocationService;
    private SeckillService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(RedisTemplate.class);
        script = mock(DefaultRedisScript.class);
        allocationService = mock(AllocationService.class);
        service = new SeckillService(redis, mock(RabbitTemplate.class), allocationService,
                mock(HotKeyDetector.class), new SimpleMeterRegistry(), mock(PendingOrderService.class), script);
        ReflectionTestUtils.setField(service, "expectedInsertions", 100);
        ReflectionTestUtils.setField(service, "fpp", 0.01d);
        service.initBloomFilter();
        when(allocationService.calculateFinalWeight(9L, 3L)).thenReturn(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exposesDistinctLuaLifecycleFailureReasons() {
        assertFailure(-1L, "活动尚未开始");
        assertFailure(-2L, "活动已结束");
        assertFailure(-3L, "活动已暂停");
        assertFailure(-4L, "优惠券已抢光");
        assertFailure(-5L, "您已参与过本次活动");
    }

    @SuppressWarnings("unchecked")
    private void assertFailure(long luaCode, String expectedMessage) {
        when(redis.execute(eq(script), anyList(), any(Object[].class))).thenReturn(luaCode);

        SeckillResponse response = service.executeSeckill(9L, 3L);

        assertEquals(false, response.isSuccess());
        assertEquals(expectedMessage, response.getMessage());
    }
}

package com.seckill.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RabbitMQConfigTest {

    @Test
    void cacheInvalidationQueueIsEphemeralWithoutLegacyMasterLocatorArgument() {
        Queue queue = new RabbitMQConfig().cacheInvalidationQueue();

        assertTrue(queue.getName().startsWith("cache.invalidation."));
        assertFalse(queue.isDurable());
        assertTrue(queue.isExclusive());
        assertTrue(queue.isAutoDelete());
        assertFalse(queue.getArguments().containsKey("x-queue-master-locator"));
    }
}

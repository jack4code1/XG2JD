package com.seckill.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
public class RabbitMQConfig {

    public static final String ORDER_CREATE_QUEUE = "order.create.queue";
    public static final String ORDER_CREATE_EXCHANGE = "order.exchange";
    public static final String ORDER_CREATE_KEY = "order.create";
    public static final String ORDER_EVENT_EXCHANGE = "order.event";

    public static final String CACHE_SYNC_QUEUE = "cache.sync.queue";
    public static final String CACHE_SYNC_EXCHANGE = "cache.sync";
    public static final String CACHE_SYNC_KEY = "cache.sync.#";

    public static final String CACHE_INVALIDATION_EXCHANGE = "cache.invalidation";

    public static final String DEAD_LETTER_QUEUE = "dead.letter.queue";
    public static final String DEAD_LETTER_EXCHANGE = "dead.letter.exchange";
    public static final String DEAD_LETTER_KEY = "dead.letter";

    @Bean
    public Queue orderCreateQueue() {
        return QueueBuilder.durable(ORDER_CREATE_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_KEY)
                .build();
    }

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_CREATE_EXCHANGE);
    }

    @Bean
    public Binding orderBinding() {
        return BindingBuilder.bind(orderCreateQueue()).to(orderExchange()).with(ORDER_CREATE_KEY);
    }

    @Bean
    public TopicExchange orderEventExchange() {
        return new TopicExchange(ORDER_EVENT_EXCHANGE);
    }

    @Bean
    public Queue cacheSyncQueue() {
        return QueueBuilder.durable(CACHE_SYNC_QUEUE).build();
    }

    @Bean
    public TopicExchange cacheSyncExchange() {
        return new TopicExchange(CACHE_SYNC_EXCHANGE);
    }

    @Bean
    public Binding cacheSyncBinding() {
        return BindingBuilder.bind(cacheSyncQueue()).to(cacheSyncExchange()).with(CACHE_SYNC_KEY);
    }

    /** Every application instance receives an invalidation event for its L1 cache. */
    @Bean
    public FanoutExchange cacheInvalidationExchange() {
        return new FanoutExchange(CACHE_INVALIDATION_EXCHANGE);
    }

    /**
     * A per-instance ephemeral queue without the legacy queue_master_locator
     * argument that RabbitMQ 4.x no longer permits.
     */
    @Bean
    public Queue cacheInvalidationQueue() {
        return QueueBuilder.nonDurable("cache.invalidation." + java.util.UUID.randomUUID())
                .exclusive()
                .autoDelete()
                .build();
    }

    @Bean
    public Binding cacheInvalidationBinding(@Qualifier("cacheInvalidationQueue") Queue cacheInvalidationQueue,
                                             @Qualifier("cacheInvalidationExchange") FanoutExchange cacheInvalidationExchange) {
        return BindingBuilder.bind(cacheInvalidationQueue).to(cacheInvalidationExchange);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(DEAD_LETTER_KEY);
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitListenerContainerFactory<?> rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        // Apply spring.rabbitmq.listener.simple.* before overriding project-specific settings.
        // Without the Boot configurer the custom factory silently falls back to one consumer
        // and the framework default prefetch, ignoring application.yml concurrency controls.
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(converter);
        factory.setDefaultRequeueRejected(false); // 转换失败不进死循环
        return factory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        // An unroutable message must trigger the Return callback instead of
        // receiving only a broker ACK and being mistaken for a delivery.
        template.setMandatory(true);
        return template;
    }
}

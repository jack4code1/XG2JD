package com.seckill.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String ORDER_CREATE_QUEUE = "order.create.queue";
    public static final String ORDER_CREATE_EXCHANGE = "order.exchange";
    public static final String ORDER_CREATE_KEY = "order.create";

    public static final String CACHE_SYNC_QUEUE = "cache.sync.queue";
    public static final String CACHE_SYNC_EXCHANGE = "cache.sync";
    public static final String CACHE_SYNC_KEY = "cache.sync.#";

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
            ConnectionFactory connectionFactory, Jackson2JsonMessageConverter converter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setDefaultRequeueRejected(false); // 转换失败不进死循环
        return factory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        return template;
    }
}
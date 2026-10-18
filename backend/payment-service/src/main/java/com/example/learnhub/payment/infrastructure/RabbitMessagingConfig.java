package com.example.learnhub.payment.infrastructure;

import com.example.learnhub.events.EventMapper;
import com.example.learnhub.events.LearningEvents;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared {@link com.example.learnhub.events.EventEnvelope} wire format onto
 * {@code RabbitTemplate} (research.md R1), and declares the {@code learning.events} exchange here too
 * (idempotently) so publishing never depends on enrollment-service having started first.
 */
@Configuration(proxyBeanMethods = false)
public class RabbitMessagingConfig {

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter(EventMapper.create());
    }

    @Bean
    public TopicExchange learningEventsExchange() {
        return new TopicExchange(LearningEvents.EXCHANGE, true, false);
    }
}

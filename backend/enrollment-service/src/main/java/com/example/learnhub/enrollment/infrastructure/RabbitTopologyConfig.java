package com.example.learnhub.enrollment.infrastructure;

import com.example.learnhub.events.EventMapper;
import com.example.learnhub.events.LearningEvents;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;

/**
 * The messaging topology for the {@code payment.succeeded} consumer (research.md R6, constitution
 * Principle V: "each consumer MUST have its own queue and its own dead-letter queue... MUST retry three
 * times with backoff before dead-lettering"). The retry-then-dead-letter behavior is wired onto a
 * dedicated listener container factory ({@link #retryingListenerContainerFactory}) via a stateless
 * retry interceptor whose {@link RepublishMessageRecoverer} explicitly publishes an exhausted message
 * to the dead-letter exchange declared below — the main queue deliberately carries no native
 * {@code x-dead-letter-exchange} argument, so the two mechanisms never compete over the same message.
 */
@Configuration(proxyBeanMethods = false)
public class RabbitTopologyConfig {

    public static final String QUEUE = "enrollment-service.payment-succeeded";
    public static final String ROUTING_KEY = "payment.succeeded";
    public static final String DEAD_LETTER_EXCHANGE = "learning.events.dlx";
    public static final String DEAD_LETTER_QUEUE = "enrollment-service.payment-succeeded.dlq";
    public static final String LISTENER_CONTAINER_FACTORY = "retryingListenerContainerFactory";
    private static final int MAX_ATTEMPTS = 3;

    /** Declared here too (idempotently) so this service never depends on publish-time declaration order. */
    @Bean
    public TopicExchange learningEventsExchange() {
        return new TopicExchange(LearningEvents.EXCHANGE, true, false);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter(EventMapper.create());
    }

    @Bean
    public Queue paymentSucceededQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    public Binding paymentSucceededBinding(Queue paymentSucceededQueue, TopicExchange learningEventsExchange) {
        return BindingBuilder.bind(paymentSucceededQueue).to(learningEventsExchange).with(ROUTING_KEY);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(ROUTING_KEY);
    }

    /**
     * Three attempts with a short fixed backoff, then {@link RepublishMessageRecoverer} publishes the
     * exhausted message (including a malformed one that fails deserialization before the listener body
     * even runs) to the dead-letter exchange and the listener acknowledges normally — never an infinite
     * requeue, and {@code defaultRequeueRejected(false)} means a message outside this retry path is
     * discarded rather than requeued to the head of the queue, so one bad message never blocks the
     * messages behind it (FR-026, FR-027).
     */
    @Bean(LISTENER_CONTAINER_FACTORY)
    public SimpleRabbitListenerContainerFactory retryingListenerContainerFactory(ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter, RabbitTemplate rabbitTemplate) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retryInterceptor(rabbitTemplate));
        // More than one consumer thread: a message stuck retrying on one thread must never prevent a
        // different, valid message from being picked up and processed by another (FR-027).
        factory.setConcurrentConsumers(3);
        return factory;
    }

    private MethodInterceptor retryInterceptor(RabbitTemplate rabbitTemplate) {
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(MAX_ATTEMPTS);
        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(1000L);
        return RetryInterceptorBuilder.stateless()
                .retryPolicy(retryPolicy)
                .backOffPolicy(backOffPolicy)
                .recoverer(new RepublishMessageRecoverer(rabbitTemplate, DEAD_LETTER_EXCHANGE, ROUTING_KEY))
                .build();
    }
}

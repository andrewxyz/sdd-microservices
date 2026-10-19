package com.example.learnhub.certificate.infrastructure;

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
 * The messaging topology for the {@code course.completed} consumer (research.md R6, constitution
 * Principle V: "each consumer MUST have its own queue and its own dead-letter queue... MUST retry three
 * times with backoff before dead-lettering"). The retry-then-dead-letter behavior is wired onto a
 * dedicated listener container factory ({@link #retryingListenerContainerFactory}) via a stateless
 * retry interceptor whose {@link RepublishMessageRecoverer} explicitly publishes an exhausted message to
 * the dead-letter exchange declared below — the main queue deliberately carries no native
 * {@code x-dead-letter-exchange} argument, so the two mechanisms never compete over the same message
 * (the same split 004 established, tasks.md T065).
 */
@Configuration(proxyBeanMethods = false)
public class RabbitTopologyConfig {

    /** The brief's own literal queue name. */
    public static final String QUEUE = "certificate.q";
    public static final String ROUTING_KEY = LearningEvents.COURSE_COMPLETED;
    /** Shared with every other consumer in the platform (research.md R6, reused from 004). */
    public static final String DEAD_LETTER_EXCHANGE = "learning.events.dlx";
    public static final String DEAD_LETTER_QUEUE = "certificate.q.dlq";
    public static final String LISTENER_CONTAINER_FACTORY = "retryingListenerContainerFactory";
    private static final int MAX_ATTEMPTS = 3;

    @Bean
    public TopicExchange learningEventsExchange() {
        return new TopicExchange(LearningEvents.EXCHANGE, true, false);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter(EventMapper.create());
    }

    @Bean
    public Queue courseCompletedQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    public Binding courseCompletedBinding(Queue courseCompletedQueue, TopicExchange learningEventsExchange) {
        return BindingBuilder.bind(courseCompletedQueue).to(learningEventsExchange).with(ROUTING_KEY);
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
     * messages behind it.
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
        // different, valid message from being picked up and processed by another.
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

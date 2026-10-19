package com.example.learnhub.notification.infrastructure;

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
 * The messaging topology for {@link com.example.learnhub.notification.application.NotificationDispatchListener}
 * (research.md R6, constitution Principle V). One queue, two bindings — the brief's literal design. The
 * retry-then-dead-letter behavior is wired onto a dedicated listener container factory (tasks.md T066),
 * the same split 004 established; since the message that is retried and ultimately dead-lettered always
 * carries its own original routing key (via {@link RepublishMessageRecoverer}), one dead-letter queue can
 * accept both event types.
 */
@Configuration(proxyBeanMethods = false)
public class RabbitTopologyConfig {

    /** The brief's own literal queue name. */
    public static final String QUEUE = "notification.q";
    public static final String DEAD_LETTER_EXCHANGE = "learning.events.dlx";
    public static final String DEAD_LETTER_QUEUE = "notification.q.dlq";
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
    public Queue notificationQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    public Binding enrollmentCreatedBinding(Queue notificationQueue, TopicExchange learningEventsExchange) {
        return BindingBuilder.bind(notificationQueue).to(learningEventsExchange)
                .with(LearningEvents.ENROLLMENT_CREATED);
    }

    @Bean
    public Binding certificateIssuedBinding(Queue notificationQueue, TopicExchange learningEventsExchange) {
        return BindingBuilder.bind(notificationQueue).to(learningEventsExchange)
                .with(LearningEvents.CERTIFICATE_ISSUED);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    /**
     * {@code RepublishMessageRecoverer} (US7) republishes with the *original* routing key the message
     * was consumed under — not literally: by default it prepends {@code "error."} to that key (an
     * implementation finding, research.md), so {@link #retryInterceptor} explicitly sets an empty prefix
     * to keep the bare routing key. This one DLQ must accept both of them.
     */
    @Bean
    public Binding deadLetterBindingEnrollment(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(LearningEvents.ENROLLMENT_CREATED);
    }

    @Bean
    public Binding deadLetterBindingCertificate(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(LearningEvents.CERTIFICATE_ISSUED);
    }

    /**
     * Three attempts with a short fixed backoff, then {@link RepublishMessageRecoverer} publishes the
     * exhausted message to the dead-letter exchange and the listener acknowledges normally — never an
     * infinite requeue. The recoverer's two-argument constructor republishes under the message's own
     * {@code receivedRoutingKey}, which is exactly why one {@code notification.q.dlq} can accept both
     * {@code enrollment.created} and {@code certificate.issued} failures.
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
        RepublishMessageRecoverer recoverer = new RepublishMessageRecoverer(rabbitTemplate, DEAD_LETTER_EXCHANGE);
        // Default prefix is "error." (e.g. "error.enrollment.created"), which would never match either
        // deadLetterBinding* above; cleared so the republished routing key is the bare original one.
        recoverer.setErrorRoutingKeyPrefix("");
        return RetryInterceptorBuilder.stateless()
                .retryPolicy(retryPolicy)
                .backOffPolicy(backOffPolicy)
                .recoverer(recoverer)
                .build();
    }
}

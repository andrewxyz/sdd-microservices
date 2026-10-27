package com.example.learnhub.analytics.infrastructure;

import com.example.learnhub.analytics.application.AnalyticsEvents;
import com.example.learnhub.events.EventMapper;
import com.example.learnhub.events.LearningEvents;
import java.util.List;
import java.util.Map;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
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
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;

/**
 * The messaging topology for {@link com.example.learnhub.analytics.application.EventDispatchListener}
 * (specs/009 research R5, constitution Principle V), the same shape as notification-service's: one queue
 * with four bindings and one dead-letter queue on the platform's existing dead-letter exchange.
 *
 * <p>Failures: an unreadable or incomplete message is rejected without retry; any other failure is retried
 * three times with a fixed backoff. Either way {@link RepublishMessageRecoverer} then publishes the message
 * to {@code learning.events.dlx} under its own routing key and the listener acknowledges, so one bad message
 * never blocks the queue.
 */
@Configuration(proxyBeanMethods = false)
public class RabbitTopologyConfig {

    public static final String QUEUE = "analytics.q";
    public static final String DEAD_LETTER_EXCHANGE = "learning.events.dlx";
    public static final String DEAD_LETTER_QUEUE = "analytics.q.dlq";
    public static final String LISTENER_CONTAINER_FACTORY = "retryingListenerContainerFactory";
    private static final int MAX_ATTEMPTS = 3;

    /** Every routing key analytics-service consumes. */
    public static final List<String> ROUTING_KEYS = List.of(AnalyticsEvents.PAYMENT_SUCCEEDED,
            LearningEvents.ENROLLMENT_CREATED, LearningEvents.COURSE_COMPLETED, LearningEvents.CERTIFICATE_ISSUED);

    @Bean
    public TopicExchange learningEventsExchange() {
        return new TopicExchange(LearningEvents.EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter(EventMapper.create());
    }

    @Bean
    public Queue analyticsQueue() {
        // The dead-letter argument is a backstop: the recoverer below normally republishes first, but a
        // message rejected by the container itself still reaches the DLQ instead of vanishing.
        return QueueBuilder.durable(QUEUE).withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE).build();
    }

    @Bean
    public Queue analyticsDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Declarables analyticsBindings(Queue analyticsQueue, Queue analyticsDeadLetterQueue,
            TopicExchange learningEventsExchange, DirectExchange deadLetterExchange) {
        List<Declarable> bindings = new java.util.ArrayList<>();
        for (String key : ROUTING_KEYS) {
            Binding main = BindingBuilder.bind(analyticsQueue).to(learningEventsExchange).with(key);
            Binding dead = BindingBuilder.bind(analyticsDeadLetterQueue).to(deadLetterExchange).with(key);
            bindings.add(main);
            bindings.add(dead);
        }
        return new Declarables(bindings);
    }

    @Bean(LISTENER_CONTAINER_FACTORY)
    public SimpleRabbitListenerContainerFactory retryingListenerContainerFactory(ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter, RabbitTemplate rabbitTemplate) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retryInterceptor(rabbitTemplate));
        // More than one consumer thread: a message stuck retrying on one thread never prevents a valid
        // message from being processed on another.
        factory.setConcurrentConsumers(3);
        return factory;
    }

    private MethodInterceptor retryInterceptor(RabbitTemplate rabbitTemplate) {
        // Malformed input is never retried (the same bytes fail the same way); everything else gets 3 attempts.
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(MAX_ATTEMPTS,
                Map.of(AmqpRejectAndDontRequeueException.class, false, MessageConversionException.class, false),
                true, true);
        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(1000L);
        RepublishMessageRecoverer recoverer = new RepublishMessageRecoverer(rabbitTemplate, DEAD_LETTER_EXCHANGE);
        // The default prefix "error." would never match the bindings above; cleared so the bare routing key stays.
        recoverer.setErrorRoutingKeyPrefix("");
        return RetryInterceptorBuilder.stateless()
                .retryPolicy(retryPolicy)
                .backOffPolicy(backOffPolicy)
                .recoverer(recoverer)
                .build();
    }
}

package com.example.learnhub.payment.application;

import com.example.learnhub.events.EventEnvelope;
import com.example.learnhub.events.EventMapper;
import com.example.learnhub.events.LearningEvents;
import com.example.learnhub.payment.domain.OutboxEvent;
import com.example.learnhub.payment.infrastructure.OutboxEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains {@link OutboxEvent} rows onto {@code learning.events} (research.md R1). Deliberately a plain
 * {@link ScheduledExecutorService} started from {@link PostConstruct}/stopped from {@link PreDestroy} —
 * the same pattern {@code platform-common}'s {@code ConfigWatcher} already uses — not
 * {@code @Scheduled}/{@code @EnableScheduling}, which {@code infra-tests}'s {@code NoWiringCodeTest}
 * forbids in every business service's source (reserved for platform-common's own config-polling
 * wiring).
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long POLL_DELAY_MILLIS = 2000;
    private static final String EVENT_TYPE = "payment.succeeded";

    private final OutboxEventRepository outboxEvents;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper mapper = EventMapper.create();

    private volatile ScheduledExecutorService scheduler;

    public OutboxPublisher(OutboxEventRepository outboxEvents, RabbitTemplate rabbitTemplate,
            PlatformTransactionManager transactionManager) {
        this.outboxEvents = outboxEvents;
        this.rabbitTemplate = rabbitTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbox-publisher");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::publishPendingSafely, POLL_DELAY_MILLIS, POLL_DELAY_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void publishPendingSafely() {
        try {
            publishPending();
        } catch (Exception e) {
            log.warn("Outbox publish cycle failed; will retry next poll", e);
        }
    }

    void publishPending() {
        List<OutboxEvent> pending = transactionTemplate.execute(status -> outboxEvents.findByPublishedAtIsNull());
        for (OutboxEvent event : pending) {
            if (publishOnce(event)) {
                transactionTemplate.executeWithoutResult(status -> {
                    event.markPublished(Instant.now());
                    outboxEvents.save(event);
                });
            }
        }
    }

    private boolean publishOnce(OutboxEvent event) {
        try {
            JsonNode payload = mapper.readTree(event.getPayloadJson());
            EventEnvelope envelope = new EventEnvelope(event.getEventId(), EVENT_TYPE, 1, Instant.now(),
                    correlationIdFor(event), payload);
            rabbitTemplate.invoke(operations -> {
                operations.convertAndSend(LearningEvents.EXCHANGE, EVENT_TYPE, envelope);
                operations.waitForConfirmsOrDie(5000);
                return null;
            });
            return true;
        } catch (Exception e) {
            log.warn("Could not publish outbox event {} yet; will retry next poll", event.getId(), e);
            return false;
        }
    }

    private String correlationIdFor(OutboxEvent event) {
        return event.getEventId().toString();
    }
}

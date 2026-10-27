package com.example.learnhub.analytics.application;

import com.example.learnhub.analytics.infrastructure.RabbitTopologyConfig;
import com.example.learnhub.common.correlation.CorrelationIds;
import com.example.learnhub.events.EventEnvelope;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The single {@code @RabbitListener} on {@code analytics.q}. The queue carries four event types through
 * separate bindings; two listeners on one queue would each lose the other's messages (the lesson recorded
 * in notification-service), so one listener dispatches on the envelope's own type.
 */
@Component
public class EventDispatchListener {

    private final StatsIngestService ingest;

    public EventDispatchListener(StatsIngestService ingest) {
        this.ingest = ingest;
    }

    @RabbitListener(queues = RabbitTopologyConfig.QUEUE,
            containerFactory = RabbitTopologyConfig.LISTENER_CONTAINER_FACTORY)
    public void onMessage(EventEnvelope envelope) {
        MDC.put(CorrelationIds.MDC_KEY, envelope.correlationId());
        try {
            ingest.handle(envelope);
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }
}

package com.example.learnhub.enrollment.application;

import com.example.learnhub.enrollment.domain.Enrollment;
import com.example.learnhub.enrollment.domain.ProcessedEvent;
import com.example.learnhub.enrollment.infrastructure.EnrollmentRepository;
import com.example.learnhub.enrollment.infrastructure.ProcessedEventRepository;
import com.example.learnhub.enrollment.infrastructure.RabbitTopologyConfig;
import com.example.learnhub.events.EventEnvelope;
import com.example.learnhub.events.EventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code payment.succeeded} (research.md R6, constitution Principle V). Idempotent by
 * {@code eventId}: a replayed event is acknowledged and otherwise ignored. A payment can only ever
 * result in an enrollment through this listener (research.md R3), so {@link Enrollment}'s own
 * {@code (user_id, course_id)} unique index is a second, independent guarantee against ever holding two
 * (FR-015).
 */
@Component
public class PaymentSucceededListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentSucceededListener.class);

    private final EnrollmentRepository enrollments;
    private final ProcessedEventRepository processedEvents;
    private final ObjectMapper mapper = EventMapper.create();

    public PaymentSucceededListener(EnrollmentRepository enrollments, ProcessedEventRepository processedEvents) {
        this.enrollments = enrollments;
        this.processedEvents = processedEvents;
    }

    @RabbitListener(queues = RabbitTopologyConfig.QUEUE, containerFactory = RabbitTopologyConfig.LISTENER_CONTAINER_FACTORY)
    @Transactional
    public void onPaymentSucceeded(EventEnvelope envelope) {
        UUID eventId = envelope.eventId();
        if (processedEvents.existsById(eventId)) {
            log.info("payment.succeeded {} already processed; ignoring replay", eventId);
            return;
        }

        PaymentSucceededPayload payload;
        try {
            payload = mapper.treeToValue(envelope.payload(), PaymentSucceededPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed payment.succeeded payload: " + eventId, e);
        }

        if (!enrollments.existsByUserIdAndCourseId(payload.userId(), payload.courseId())) {
            enrollments.save(new Enrollment(UUID.randomUUID(), payload.userId(), payload.courseId(),
                    Instant.now(), payload.paymentId()));
        }
        processedEvents.save(new ProcessedEvent(eventId, Instant.now()));
    }
}

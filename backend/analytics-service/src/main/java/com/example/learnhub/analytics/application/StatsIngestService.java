package com.example.learnhub.analytics.application;

import com.example.learnhub.analytics.infrastructure.ProcessedEventRepository;
import com.example.learnhub.analytics.infrastructure.StatsRepository;
import com.example.learnhub.events.CertificateIssuedPayload;
import com.example.learnhub.events.CourseCompletedPayload;
import com.example.learnhub.events.EnrollmentCreatedPayload;
import com.example.learnhub.events.EventEnvelope;
import com.example.learnhub.events.EventMapper;
import com.example.learnhub.events.LearningEvents;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns one consumed event into one counter increment (spec FR-012, FR-014; research R2, R3, R4):
 * the event id is recorded first and a duplicate is skipped, then the matching counter for the course and
 * the UTC day of the envelope's {@code occurredAt} is increased, all in one transaction.
 *
 * <p>Only the course id and, for payments, the amount are read from a payload. Nothing identifying a
 * person is stored or logged, and nothing here calls another service (constitution XIV).
 */
@Service
public class StatsIngestService {

    private static final Logger log = LoggerFactory.getLogger(StatsIngestService.class);

    private record Delta(UUID courseId, long enrollments, long revenueCents, long completions, long certificates) {
    }

    private final ProcessedEventRepository processedEvents;
    private final StatsRepository stats;
    private final ObjectMapper mapper = EventMapper.create();

    public StatsIngestService(ProcessedEventRepository processedEvents, StatsRepository stats) {
        this.processedEvents = processedEvents;
        this.stats = stats;
    }

    @Transactional
    public void handle(EventEnvelope envelope) {
        Delta delta = parse(envelope);

        if (processedEvents.markProcessed(envelope.eventId()) == 0) {
            log.info("{} {} already counted; ignoring replay", envelope.type(), envelope.eventId());
            return;
        }

        LocalDate day = envelope.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
        stats.add(delta.courseId(), day, delta.enrollments(), delta.revenueCents(), delta.completions(),
                delta.certificates());
    }

    private Delta parse(EventEnvelope envelope) {
        String type = envelope.type();
        if (!isSupported(type)) {
            // Retried, then dead-lettered: an unexpected type on this queue is a wiring problem to look at.
            throw new IllegalArgumentException("Unexpected event type on analytics.q: " + type);
        }
        try {
            switch (type) {
                case AnalyticsEvents.PAYMENT_SUCCEEDED -> {
                    PaymentSucceededPayload p = mapper.treeToValue(envelope.payload(), PaymentSucceededPayload.class);
                    require(p.courseId() != null && p.amountCents() >= 0, "course and non-negative amount");
                    return new Delta(p.courseId(), 0, p.amountCents(), 0, 0);
                }
                case LearningEvents.ENROLLMENT_CREATED -> {
                    EnrollmentCreatedPayload p = mapper.treeToValue(envelope.payload(), EnrollmentCreatedPayload.class);
                    require(p.courseId() != null, "course");
                    return new Delta(p.courseId(), 1, 0, 0, 0);
                }
                case LearningEvents.COURSE_COMPLETED -> {
                    CourseCompletedPayload p = mapper.treeToValue(envelope.payload(), CourseCompletedPayload.class);
                    require(p.courseId() != null, "course");
                    return new Delta(p.courseId(), 0, 0, 1, 0);
                }
                default -> {
                    CertificateIssuedPayload p = mapper.treeToValue(envelope.payload(), CertificateIssuedPayload.class);
                    require(p.courseId() != null, "course");
                    return new Delta(p.courseId(), 0, 0, 0, 1);
                }
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // Never retried: the same bytes will fail the same way. The message is kept free of payload values.
            throw new AmqpRejectAndDontRequeueException(
                    "Malformed " + type + " payload for event " + envelope.eventId(), e);
        }
    }

    private static boolean isSupported(String type) {
        return AnalyticsEvents.PAYMENT_SUCCEEDED.equals(type) || LearningEvents.ENROLLMENT_CREATED.equals(type)
                || LearningEvents.COURSE_COMPLETED.equals(type) || LearningEvents.CERTIFICATE_ISSUED.equals(type);
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException("payload lacks " + what);
        }
    }
}

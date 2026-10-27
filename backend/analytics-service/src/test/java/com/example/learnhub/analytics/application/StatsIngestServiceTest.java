package com.example.learnhub.analytics.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.learnhub.analytics.infrastructure.ProcessedEventRepository;
import com.example.learnhub.analytics.infrastructure.StatsRepository;
import com.example.learnhub.events.EventEnvelope;
import com.example.learnhub.events.LearningEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

/** FR-014, FR-018, research R3/R4: what each event does to the counters, with fake storage. */
class StatsIngestServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProcessedEventRepository processed;
    private StatsRepository stats;
    private StatsIngestService service;
    private final UUID courseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        processed = mock(ProcessedEventRepository.class);
        stats = mock(StatsRepository.class);
        when(processed.markProcessed(any())).thenReturn(1);
        service = new StatsIngestService(processed, stats);
    }

    private EventEnvelope envelope(String type, ObjectNode payload, Instant occurredAt) {
        return new EventEnvelope(UUID.randomUUID(), type, 1, occurredAt, "corr-0001-test", payload);
    }

    private ObjectNode payment(int amountCents) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("paymentId", UUID.randomUUID().toString());
        payload.put("userId", UUID.randomUUID().toString());
        payload.put("courseId", courseId.toString());
        payload.put("amountCents", amountCents);
        payload.put("currency", "USD");
        payload.put("occurredAt", "2026-03-01T10:00:00Z");
        return payload;
    }

    private ObjectNode courseEvent(String idField, String timeField) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put(idField, UUID.randomUUID().toString());
        payload.put("userId", UUID.randomUUID().toString());
        payload.put("courseId", courseId.toString());
        payload.put(timeField, "2026-03-01T10:00:00Z");
        return payload;
    }

    @Test
    void paymentSucceededAddsTheAmountToRevenue() {
        service.handle(envelope("payment.succeeded", payment(1999), Instant.parse("2026-03-01T10:00:00Z")));

        verify(stats).add(courseId, LocalDate.of(2026, 3, 1), 0, 1999, 0, 0);
    }

    @Test
    void enrollmentCreatedAddsOneEnrollment() {
        ObjectNode payload = courseEvent("enrollmentId", "enrolledAt");

        service.handle(envelope(LearningEvents.ENROLLMENT_CREATED, payload, Instant.parse("2026-03-01T10:00:00Z")));

        verify(stats).add(courseId, LocalDate.of(2026, 3, 1), 1, 0, 0, 0);
    }

    @Test
    void courseCompletedAddsOneCompletion() {
        ObjectNode payload = courseEvent("enrollmentId", "completedAt");

        service.handle(envelope(LearningEvents.COURSE_COMPLETED, payload, Instant.parse("2026-03-01T10:00:00Z")));

        verify(stats).add(courseId, LocalDate.of(2026, 3, 1), 0, 0, 1, 0);
    }

    @Test
    void certificateIssuedAddsOneCertificate() {
        ObjectNode payload = courseEvent("certificateId", "issuedAt");
        payload.put("verificationCode", "ABCDEF0123456789");

        service.handle(envelope(LearningEvents.CERTIFICATE_ISSUED, payload, Instant.parse("2026-03-01T10:00:00Z")));

        verify(stats).add(courseId, LocalDate.of(2026, 3, 1), 0, 0, 0, 1);
    }

    @Test
    void theDayIsTheUtcDateOfTheEnvelopeNotTheLocalOne() {
        // 23:30 at UTC-05:00 is 04:30 UTC on the next day.
        Instant lateEvening = java.time.OffsetDateTime.parse("2026-03-01T23:30:00-05:00").toInstant();

        service.handle(envelope("payment.succeeded", payment(500), lateEvening));

        verify(stats).add(courseId, LocalDate.of(2026, 3, 2), 0, 500, 0, 0);
    }

    @Test
    void aDuplicateEventIdChangesNothing() {
        when(processed.markProcessed(any())).thenReturn(0);

        service.handle(envelope("payment.succeeded", payment(1999), Instant.parse("2026-03-01T10:00:00Z")));

        verify(stats, never()).add(any(), any(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void aPayloadWithoutACourseIdIsRejectedWithoutRetryAndNothingIsRecorded() {
        ObjectNode payload = payment(100);
        payload.remove("courseId");

        assertThatThrownBy(() -> service.handle(envelope("payment.succeeded", payload, Instant.now())))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(processed, stats);
    }

    @Test
    void anUnreadablePayloadIsRejectedWithoutRetry() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("courseId", "not-a-uuid");

        assertThatThrownBy(() -> service.handle(envelope(LearningEvents.ENROLLMENT_CREATED, payload, Instant.now())))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(processed, stats);
    }

    @Test
    void aNegativeAmountIsRejected() {
        assertThatThrownBy(() -> service.handle(envelope("payment.succeeded", payment(-1), Instant.now())))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    void anUnsupportedTypeFailsSoItIsRetriedThenDeadLettered() {
        ObjectNode payload = MAPPER.createObjectNode();

        assertThatThrownBy(() -> service.handle(envelope("review.created", payload, Instant.now())))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(AmqpRejectAndDontRequeueException.class);
    }
}

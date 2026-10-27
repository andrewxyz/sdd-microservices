package com.example.learnhub.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.analytics.support.AbstractAnalyticsServiceIT;
import com.example.learnhub.analytics.support.EventSamples;
import com.example.learnhub.events.EventEnvelope;
import com.example.learnhub.events.LearningEvents;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** US4 (spec FR-014, SC-004): delivering or replaying an event more than once changes the figures once. */
class IdempotentIngestIT extends AbstractAnalyticsServiceIT {

    @Test
    void theSameEnrollmentDeliveredTwiceCountsOnce() throws Exception {
        UUID courseId = UUID.randomUUID();
        EventEnvelope first = publish(UUID.randomUUID(), LearningEvents.ENROLLMENT_CREATED,
                EventSamples.enrollmentCreated(courseId), Instant.now());
        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, LearningEvents.ENROLLMENT_CREATED, first);

        assertThat(eventually(() -> sum(courseId, "enrollments") >= 1, 20_000)).isTrue();
        Thread.sleep(1500); // let the second delivery be consumed too
        assertThat(sum(courseId, "enrollments")).isEqualTo(1);
        assertThat(processedCount()).isEqualTo(1);
    }

    @Test
    void aReplayLongAfterTheFirstDeliveryChangesNothing() throws Exception {
        UUID courseId = UUID.randomUUID();
        EventEnvelope payment = publish(UUID.randomUUID(), "payment.succeeded", EventSamples.payment(courseId, 1999),
                Instant.now());
        assertThat(eventually(() -> sum(courseId, "revenue_cents") == 1999, 20_000)).isTrue();

        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, "payment.succeeded", payment);
        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, "payment.succeeded", payment);
        Thread.sleep(1500);

        assertThat(sum(courseId, "revenue_cents")).isEqualTo(1999);
        assertThat(processedCount()).isEqualTo(1);
    }

    @Test
    void everyEventTypeIsIdempotent() throws Exception {
        UUID courseId = UUID.randomUUID();
        EventEnvelope[] sent = {
                publish(UUID.randomUUID(), "payment.succeeded", EventSamples.payment(courseId, 500), Instant.now()),
                publish(UUID.randomUUID(), LearningEvents.ENROLLMENT_CREATED, EventSamples.enrollmentCreated(courseId),
                        Instant.now()),
                publish(UUID.randomUUID(), LearningEvents.COURSE_COMPLETED, EventSamples.courseCompleted(courseId),
                        Instant.now()),
                publish(UUID.randomUUID(), LearningEvents.CERTIFICATE_ISSUED, EventSamples.certificateIssued(courseId),
                        Instant.now()),
        };
        for (EventEnvelope envelope : sent) {
            rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, envelope.type(), envelope);
        }

        assertThat(eventually(() -> processedCount() == 4, 20_000)).isTrue();
        Thread.sleep(1500);

        assertThat(sum(courseId, "revenue_cents")).isEqualTo(500);
        assertThat(sum(courseId, "enrollments")).isEqualTo(1);
        assertThat(sum(courseId, "completions")).isEqualTo(1);
        assertThat(sum(courseId, "certificates")).isEqualTo(1);
    }
}

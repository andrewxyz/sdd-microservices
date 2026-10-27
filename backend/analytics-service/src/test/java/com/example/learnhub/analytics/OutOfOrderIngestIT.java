package com.example.learnhub.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.analytics.support.AbstractAnalyticsServiceIT;
import com.example.learnhub.analytics.support.EventSamples;
import com.example.learnhub.events.LearningEvents;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** US4 (research R4): arrival order does not matter; the counters only ever add. */
class OutOfOrderIngestIT extends AbstractAnalyticsServiceIT {

    private void inNaturalOrder(UUID courseId, Instant at) {
        publish(UUID.randomUUID(), "payment.succeeded", EventSamples.payment(courseId, 2500), at);
        publish(UUID.randomUUID(), LearningEvents.ENROLLMENT_CREATED, EventSamples.enrollmentCreated(courseId), at);
        publish(UUID.randomUUID(), LearningEvents.COURSE_COMPLETED, EventSamples.courseCompleted(courseId), at);
        publish(UUID.randomUUID(), LearningEvents.CERTIFICATE_ISSUED, EventSamples.certificateIssued(courseId), at);
    }

    private void certificateFirst(UUID courseId, Instant at) {
        publish(UUID.randomUUID(), LearningEvents.CERTIFICATE_ISSUED, EventSamples.certificateIssued(courseId), at);
        publish(UUID.randomUUID(), LearningEvents.COURSE_COMPLETED, EventSamples.courseCompleted(courseId), at);
        publish(UUID.randomUUID(), LearningEvents.ENROLLMENT_CREATED, EventSamples.enrollmentCreated(courseId), at);
        publish(UUID.randomUUID(), "payment.succeeded", EventSamples.payment(courseId, 2500), at);
    }

    @Test
    void aShuffledSequenceGivesTheSameTotalsAsTheNaturalOne() throws Exception {
        UUID natural = UUID.randomUUID();
        UUID shuffled = UUID.randomUUID();
        Instant at = Instant.now();

        inNaturalOrder(natural, at);
        certificateFirst(shuffled, at);

        assertThat(eventually(() -> processedCount() == 8, 30_000)).as("all eight events processed").isTrue();
        for (String column : new String[] {"enrollments", "revenue_cents", "completions", "certificates"}) {
            assertThat(sum(shuffled, column)).as(column).isEqualTo(sum(natural, column));
        }
        assertThat(sum(shuffled, "revenue_cents")).isEqualTo(2500);
        assertThat(sum(shuffled, "certificates")).isEqualTo(1);
    }

    @Test
    void aCompletionBeforeAnyEnrollmentIsStillCounted() throws Exception {
        UUID courseId = UUID.randomUUID();

        publish(UUID.randomUUID(), LearningEvents.COURSE_COMPLETED, EventSamples.courseCompleted(courseId), Instant.now());

        assertThat(eventually(() -> sum(courseId, "completions") == 1, 20_000)).isTrue();
        assertThat(sum(courseId, "enrollments")).isZero();
    }
}

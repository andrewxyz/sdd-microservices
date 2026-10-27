package com.example.learnhub.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.analytics.support.AbstractAnalyticsServiceIT;
import com.example.learnhub.analytics.support.EventSamples;
import com.example.learnhub.events.LearningEvents;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** US4 (spec FR-018): the day of a count is the UTC date of the event time, whatever zone it was written in. */
class UtcDayBoundaryIT extends AbstractAnalyticsServiceIT {

    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate MARCH_2 = LocalDate.of(2026, 3, 2);

    @Test
    void eventsOneMillisecondEitherSideOfMidnightUtcLandOnDifferentDays() throws Exception {
        UUID courseId = UUID.randomUUID();

        publish(UUID.randomUUID(), LearningEvents.ENROLLMENT_CREATED, EventSamples.enrollmentCreated(courseId),
                Instant.parse("2026-03-01T23:59:59.999Z"));
        publish(UUID.randomUUID(), LearningEvents.ENROLLMENT_CREATED, EventSamples.enrollmentCreated(courseId),
                Instant.parse("2026-03-02T00:00:00Z"));

        assertThat(eventually(() -> processedCount() == 2, 20_000)).isTrue();
        assertThat(onDay(courseId, MARCH_1, "enrollments")).isEqualTo(1);
        assertThat(onDay(courseId, MARCH_2, "enrollments")).isEqualTo(1);
    }

    @Test
    void anEventWrittenWithAnOffsetIsBucketedByItsUtcDate() throws Exception {
        UUID courseId = UUID.randomUUID();
        // 22:30 on 1 March at UTC-05:00 is 03:30 on 2 March in UTC.
        Instant eveningInNewYork = OffsetDateTime.parse("2026-03-01T22:30:00-05:00").toInstant();

        publish(UUID.randomUUID(), "payment.succeeded", EventSamples.payment(courseId, 900), eveningInNewYork);

        assertThat(eventually(() -> sum(courseId, "revenue_cents") == 900, 20_000)).isTrue();
        assertThat(onDay(courseId, MARCH_2, "revenue_cents")).isEqualTo(900);
        assertThat(onDay(courseId, MARCH_1, "revenue_cents")).isZero();
    }
}

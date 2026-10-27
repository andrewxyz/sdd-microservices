package com.example.learnhub.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.analytics.support.AbstractAnalyticsServiceIT;
import com.example.learnhub.analytics.support.EventSamples;
import com.example.learnhub.events.LearningEvents;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** US4 (research R4): 20 events for one course and day, sent at the same moment, add up exactly. */
class ConcurrentIngestIT extends AbstractAnalyticsServiceIT {

    @Test
    void twentyConcurrentEventsGiveExactSums() throws Exception {
        UUID courseId = UUID.randomUUID();
        Instant now = Instant.now();
        List<Runnable> sends = new ArrayList<>();
        long expectedRevenue = 0;
        for (int i = 1; i <= 5; i++) {
            int amount = i * 111;
            expectedRevenue += amount;
            sends.add(() -> publish(UUID.randomUUID(), "payment.succeeded", EventSamples.payment(courseId, amount), now));
            sends.add(() -> publish(UUID.randomUUID(), LearningEvents.ENROLLMENT_CREATED,
                    EventSamples.enrollmentCreated(courseId), now));
            sends.add(() -> publish(UUID.randomUUID(), LearningEvents.COURSE_COMPLETED,
                    EventSamples.courseCompleted(courseId), now));
            sends.add(() -> publish(UUID.randomUUID(), LearningEvents.CERTIFICATE_ISSUED,
                    EventSamples.certificateIssued(courseId), now));
        }
        assertThat(sends).hasSize(20);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            sends.forEach(send -> pool.submit(() -> {
                start.await();
                send.run();
                return null;
            }));
            start.countDown();
        } finally {
            pool.shutdown();
        }

        assertThat(eventually(() -> processedCount() == 20, 40_000)).as("all 20 events processed").isTrue();
        assertThat(sum(courseId, "enrollments")).isEqualTo(5);
        assertThat(sum(courseId, "revenue_cents")).isEqualTo(expectedRevenue);
        assertThat(sum(courseId, "completions")).isEqualTo(5);
        assertThat(sum(courseId, "certificates")).isEqualTo(5);
    }

    @Test
    void manyEnrollmentsForTheSameCourseAndDayAllLand() throws Exception {
        UUID courseId = UUID.randomUUID();
        Instant now = Instant.now();
        int count = 20;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            for (int i = 0; i < count; i++) {
                pool.submit(() -> {
                    start.await();
                    publish(UUID.randomUUID(), LearningEvents.ENROLLMENT_CREATED, EventSamples.enrollmentCreated(courseId), now);
                    return null;
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
        }

        assertThat(eventually(() -> sum(courseId, "enrollments") == count, 40_000)).isTrue();
        assertThat(processedCount()).isEqualTo(count);
    }
}

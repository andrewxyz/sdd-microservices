package com.example.learnhub.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.learnhub.analytics.support.AbstractAnalyticsServiceIT;
import com.example.learnhub.analytics.support.EventSamples;
import com.example.learnhub.events.LearningEvents;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spec FR-011, SC-005, research R3: events carry user, payment, enrollment and certificate ids; none of
 * them may end up in storage, in an API response, or in the logs.
 */
@ExtendWith(OutputCaptureExtension.class)
class NoPersonalDataIT extends AbstractAnalyticsServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void identifiersOfPeopleAndTransactionsAreNeverStoredReturnedOrLogged(CapturedOutput output) throws Exception {
        UUID courseId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID enrollmentId = UUID.randomUUID();
        UUID certificateId = UUID.randomUUID();
        List<UUID> secrets = List.of(userId, paymentId, enrollmentId, certificateId);
        Instant now = Instant.now();

        publish(UUID.randomUUID(), "payment.succeeded", EventSamples.payment(courseId, 1999, userId, paymentId), now);
        publish(UUID.randomUUID(), LearningEvents.ENROLLMENT_CREATED,
                EventSamples.enrollmentCreated(courseId, userId, enrollmentId), now);
        publish(UUID.randomUUID(), LearningEvents.COURSE_COMPLETED,
                EventSamples.courseCompleted(courseId, userId, enrollmentId), now);
        publish(UUID.randomUUID(), LearningEvents.CERTIFICATE_ISSUED,
                EventSamples.certificateIssued(courseId, userId, certificateId), now);

        assertThat(eventually(() -> count("processed_events") == 4, 20_000)).as("all four events processed").isTrue();

        String stored = jdbc.queryForList("SELECT * FROM course_daily_stats").toString()
                + jdbc.queryForList("SELECT * FROM processed_events");
        assertThat(count("course_daily_stats")).isEqualTo(1);
        for (UUID secret : secrets) {
            assertThat(stored).as("storage must not hold %s", secret).doesNotContain(secret.toString());
        }

        COURSE_SERVICE.courseOwnedBy(courseId, UUID.randomUUID());
        String summary = mockMvc.perform(get("/api/v1/instructor/stats/summary")
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "ADMINISTRATOR"))
                .andReturn().getResponse().getContentAsString();
        String list = mockMvc.perform(get("/api/v1/instructor/stats/courses")
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "ADMINISTRATOR"))
                .andReturn().getResponse().getContentAsString();
        String detail = mockMvc.perform(get("/api/v1/instructor/stats/courses/" + courseId)
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "ADMINISTRATOR"))
                .andReturn().getResponse().getContentAsString();

        for (UUID secret : secrets) {
            assertThat(summary + list + detail).as("responses must not hold %s", secret).doesNotContain(secret.toString());
            assertThat(output.getAll()).as("logs must not hold %s", secret).doesNotContain(secret.toString());
        }
        assertThat(list).contains(courseId.toString());
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }
}

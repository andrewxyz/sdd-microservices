package com.example.learnhub.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.analytics.support.AbstractAnalyticsServiceIT;
import com.example.learnhub.analytics.support.EventSamples;
import com.example.learnhub.events.EventEnvelope;
import com.example.learnhub.events.LearningEvents;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

/** US4 (spec FR-015): a message that cannot be counted is set aside and never blocks the ones behind it. */
class MalformedMessageIT extends AbstractAnalyticsServiceIT {

    @Test
    void aPayloadWithAnUnreadableCourseIdGoesToTheDeadLetterQueue() throws Exception {
        ObjectNode payload = EventSamples.enrollmentCreated(UUID.randomUUID());
        payload.put("courseId", "not-a-uuid");

        publish(UUID.randomUUID(), LearningEvents.ENROLLMENT_CREATED, payload, Instant.now());

        assertThat(eventually(() -> queueDepth("analytics.q.dlq") == 1, 30_000)).as("in the dead-letter queue").isTrue();
        assertThat(processedCount()).as("a rejected message is not recorded as counted").isZero();
    }

    @Test
    void aPayloadWithNoCourseIdGoesToTheDeadLetterQueue() throws Exception {
        ObjectNode payload = EventSamples.payment(UUID.randomUUID(), 100);
        payload.remove("courseId");

        publish(UUID.randomUUID(), "payment.succeeded", payload, Instant.now());

        assertThat(eventually(() -> queueDepth("analytics.q.dlq") == 1, 30_000)).isTrue();
    }

    @Test
    void aBodyThatIsNotAnEnvelopeGoesToTheDeadLetterQueue() throws Exception {
        Message garbage = MessageBuilder.withBody("{\"not\":\"an envelope\"}".getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();

        rabbitTemplate.send(LearningEvents.EXCHANGE, "payment.succeeded", garbage);

        assertThat(eventually(() -> queueDepth("analytics.q.dlq") == 1, 30_000)).isTrue();
    }

    @Test
    void aMessageWhoseTypeDoesNotMatchIsRetriedThenDeadLettered() throws Exception {
        EventEnvelope mismatched = new EventEnvelope(UUID.randomUUID(), "review.created", 1, Instant.now(),
                "it-" + UUID.randomUUID(), EventSamples.payment(UUID.randomUUID(), 100));

        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, "payment.succeeded", mismatched);

        assertThat(eventually(() -> queueDepth("analytics.q.dlq") == 1, 40_000)).isTrue();
        assertThat(queueDepth("analytics.q")).isZero();
    }

    @Test
    void validMessagesBehindABadOneAreStillCounted() throws Exception {
        UUID courseId = UUID.randomUUID();
        ObjectNode bad = EventSamples.enrollmentCreated(courseId);
        bad.put("courseId", "not-a-uuid");
        EventEnvelope mismatched = new EventEnvelope(UUID.randomUUID(), "review.created", 1, Instant.now(),
                "it-" + UUID.randomUUID(), EventSamples.payment(courseId, 100));

        publish(UUID.randomUUID(), LearningEvents.ENROLLMENT_CREATED, bad, Instant.now());
        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, "payment.succeeded", mismatched);
        for (int i = 0; i < 5; i++) {
            publish(UUID.randomUUID(), LearningEvents.ENROLLMENT_CREATED, EventSamples.enrollmentCreated(courseId),
                    Instant.now());
        }

        assertThat(eventually(() -> sum(courseId, "enrollments") == 5, 30_000)).as("good events counted").isTrue();
        assertThat(eventually(() -> queueDepth("analytics.q.dlq") == 2, 40_000)).as("bad events set aside").isTrue();
    }
}

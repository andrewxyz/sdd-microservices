package com.example.learnhub.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.events.EventEnvelope;
import com.example.learnhub.events.LearningEvents;
import com.example.learnhub.payment.domain.OutboxEvent;
import com.example.learnhub.payment.infrastructure.OutboxEventRepository;
import com.example.learnhub.payment.support.AbstractPaymentServiceIT;
import com.example.learnhub.payment.support.CourseServiceStub;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** FR-025, research.md R1: a successful payment's event is durably recorded and then published. */
class OutboxPublisherIT extends AbstractPaymentServiceIT {

    private static final CourseServiceStub COURSE_SERVICE = new CourseServiceStub();

    static {
        COURSE_SERVICE.start();
    }

    @DynamicPropertySource
    static void courseServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.course-service.url", COURSE_SERVICE::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        COURSE_SERVICE.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Test
    void successfulCheckoutIsPublishedOntoLearningEvents() throws Exception {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, UUID.randomUUID(), 0);

        ConnectionFactory rabbitClientFactory = new ConnectionFactory();
        rabbitClientFactory.setHost(RABBITMQ.getHost());
        rabbitClientFactory.setPort(RABBITMQ.getAmqpPort());
        rabbitClientFactory.setUsername(RABBITMQ.getAdminUsername());
        rabbitClientFactory.setPassword(RABBITMQ.getAdminPassword());

        try (Connection connection = rabbitClientFactory.newConnection();
                Channel channel = connection.createChannel()) {
            channel.exchangeDeclare(LearningEvents.EXCHANGE, BuiltinExchangeType.TOPIC, true);
            String queue = channel.queueDeclare().getQueue();
            channel.queueBind(queue, LearningEvents.EXCHANGE, "payment.succeeded");

            mockMvc.perform(post("/api/v1/checkouts")
                            .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"courseId\":\"%s\",\"idempotencyKey\":\"outbox-1\"}".formatted(courseId)))
                    .andExpect(status().isCreated());

            ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
            com.rabbitmq.client.GetResponse response = Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .until(() -> channel.basicGet(queue, true), r -> r != null);
            EventEnvelope envelope = mapper.readValue(response.getBody(), EventEnvelope.class);

            assertThat(envelope.type()).isEqualTo("payment.succeeded");
            assertThat(envelope.payload().get("courseId").asText()).isEqualTo(courseId.toString());
            assertThat(envelope.payload().get("amountCents").asInt()).isEqualTo(0);
        }

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<OutboxEvent> unpublished = outboxEvents.findByPublishedAtIsNull();
            assertThat(unpublished).isEmpty();
        });
    }

    /** FR-025, research.md R1: the broker, not just enrollment-service, can be briefly unreachable. */
    @Test
    void aPaymentThatSucceedsDuringABrokerOutageIsPublishedOnceTheBrokerRecovers() throws Exception {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, UUID.randomUUID(), 0);

        RABBITMQ.getDockerClient().pauseContainerCmd(RABBITMQ.getContainerId()).exec();
        try {
            mockMvc.perform(post("/api/v1/checkouts")
                            .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"courseId\":\"%s\",\"idempotencyKey\":\"outbox-outage-1\"}".formatted(courseId)))
                    .andExpect(status().isCreated());

            // The checkout itself succeeds regardless (R1's whole point); give the publisher a couple of
            // poll cycles to prove it genuinely cannot get through while the broker is paused.
            Thread.sleep(4000);
            assertThat(outboxEvents.findByPublishedAtIsNull()).isNotEmpty();
        } finally {
            RABBITMQ.getDockerClient().unpauseContainerCmd(RABBITMQ.getContainerId()).exec();
        }

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(outboxEvents.findByPublishedAtIsNull()).isEmpty());
    }
}

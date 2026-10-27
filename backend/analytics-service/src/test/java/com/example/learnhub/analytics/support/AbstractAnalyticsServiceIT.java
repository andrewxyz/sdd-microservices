package com.example.learnhub.analytics.support;

import com.example.learnhub.events.EventEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base for analytics-service integration tests: a real PostgreSQL 16 and a real RabbitMQ 4.3
 * (constitution Principle VI), as singleton containers like every other {@code Abstract*ServiceIT}.
 * Each test starts from empty counters and an empty queue.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
public abstract class AbstractAnalyticsServiceIT {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("analytics")
                    .withUsername("analytics")
                    .withPassword("analytics-test-password");

    protected static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3-management"));

    protected static final CourseServiceStub COURSE_SERVICE = new CourseServiceStub();

    static {
        POSTGRES.start();
        RABBITMQ.start();
        COURSE_SERVICE.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);

        registry.add("course-service.url", COURSE_SERVICE::baseUrl);
    }

    @Autowired
    protected RabbitTemplate rabbitTemplate;

    @Autowired
    protected AmqpAdmin amqpAdmin;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        COURSE_SERVICE.reset();
        jdbc.update("DELETE FROM course_daily_stats");
        jdbc.update("DELETE FROM processed_events");
        amqpAdmin.purgeQueue("analytics.q", true);
        amqpAdmin.purgeQueue("analytics.q.dlq", true);
    }

    /** Publishes a versioned envelope the way the producing services do. */
    protected EventEnvelope publish(UUID eventId, String type, JsonNode payload, Instant occurredAt) {
        EventEnvelope envelope = new EventEnvelope(eventId, type, 1, occurredAt, "it-" + UUID.randomUUID(), payload);
        rabbitTemplate.convertAndSend("learning.events", type, envelope);
        return envelope;
    }

    protected int queueDepth(String queue) {
        var properties = amqpAdmin.getQueueProperties(queue);
        return properties == null ? -1 : (Integer) properties.get("QUEUE_MESSAGE_COUNT");
    }

    /** Sum of one counter column for a course across all days ({@code enrollments}, {@code revenue_cents}, ...). */
    protected long sum(UUID courseId, String column) {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(" + column + "), 0) FROM course_daily_stats WHERE course_id = ?", Long.class,
                courseId);
        return value == null ? 0 : value;
    }

    /** One counter column for a course on one UTC day; 0 when there is no row. */
    protected long onDay(UUID courseId, java.time.LocalDate day, String column) {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(" + column + "), 0) FROM course_daily_stats WHERE course_id = ? AND day = ?",
                Long.class, courseId, java.sql.Date.valueOf(day));
        return value == null ? 0 : value;
    }

    protected long processedCount() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM processed_events", Long.class);
        return value == null ? 0 : value;
    }

    /** Polls until the condition holds or the timeout elapses; returns whether it held. */
    protected static boolean eventually(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }
}

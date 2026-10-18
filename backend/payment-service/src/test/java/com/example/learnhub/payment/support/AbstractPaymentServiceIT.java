package com.example.learnhub.payment.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base for payment-service integration tests: a real PostgreSQL 16 and a real RabbitMQ 4.3
 * (constitution Principle VI — Testcontainers, never H2, never a mocked broker), with MockMvc for
 * driving the HTTP layer.
 *
 * <p>Deliberately the Testcontainers "singleton container" pattern, not {@code @Testcontainers}
 * +{@code @Container} — see course-service's own {@code AbstractCourseServiceIT} for the full
 * rationale: every subclass here has identical {@code @SpringBootTest} configuration, so Spring's test
 * context cache reuses one {@code ApplicationContext} across all of them, and a restarted container
 * mid-run would hand the cached context's already-built connections a new, unknown host port. Starting
 * both containers once, here, and never stopping them (Ryuk cleans up at JVM exit) keeps one container,
 * one port, for the whole test run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
public abstract class AbstractPaymentServiceIT {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("payment")
                    .withUsername("payment")
                    .withPassword("payment-test-password");

    protected static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3-management"));

    static {
        POSTGRES.start();
        RABBITMQ.start();
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
    }
}

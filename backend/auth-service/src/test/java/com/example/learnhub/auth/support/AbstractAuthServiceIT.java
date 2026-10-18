package com.example.learnhub.auth.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base for auth-service integration tests: a real PostgreSQL 16 (constitution Principle VI:
 * Testcontainers, never H2) with Flyway migrating it on context start, and MockMvc for driving the
 * HTTP layer without binding a real port.
 *
 * <p>Deliberately the Testcontainers "singleton container" pattern, not {@code @Testcontainers}
 * +{@code @Container}: every subclass here has identical {@code @SpringBootTest} configuration, so
 * Spring's test context cache reuses one {@code ApplicationContext} (and therefore one already-opened
 * JDBC URL) across all of them. {@code @Container} on a static field stops the container after the
 * *first* subclass's tests finish, and restarting it for the next subclass hands it a new host port —
 * which the cached context's already-built DataSource never learns about, so every subsequent class
 * fails with "connection refused" to the old port. Starting it once, here, and never stopping it (Ryuk
 * cleans it up when the JVM exits) keeps one container, one port, for the whole test run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
public abstract class AbstractAuthServiceIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
            .withDatabaseName("auth")
            .withUsername("auth")
            .withPassword("auth-test-password");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}

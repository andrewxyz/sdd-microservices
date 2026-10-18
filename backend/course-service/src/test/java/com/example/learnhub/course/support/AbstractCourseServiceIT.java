package com.example.learnhub.course.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base for course-service integration tests: a real PostgreSQL 16 (constitution Principle VI)
 * with Flyway migrating it on context start, and MockMvc for driving the HTTP layer.
 *
 * <p>Deliberately the Testcontainers "singleton container" pattern, not {@code @Testcontainers}
 * +{@code @Container} (see auth-service's own {@code AbstractAuthServiceIT} for the full rationale,
 * written after that exact mistake broke every test class but the first one in this module's sibling):
 * every subclass here has identical {@code @SpringBootTest} configuration, so Spring's test context
 * cache reuses one {@code ApplicationContext} across all of them, and {@code @Container} on a static
 * field stops the container after the first subclass's tests finish — restarting it for the next
 * subclass hands it a new host port the cached context's already-built DataSource never learns about.
 * Starting it once, here, and never stopping it (Ryuk cleans it up when the JVM exits) keeps one
 * container, one port, for the whole test run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
public abstract class AbstractCourseServiceIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
            .withDatabaseName("course")
            .withUsername("course")
            .withPassword("course-test-password");

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

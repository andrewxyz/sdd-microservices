package com.example.learnhub.echo;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.common.correlation.CorrelationIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * FR-020: logs are structured and carry the correlation ID.
 *
 * <p>Asserts the line really parses as ECS JSON with the ID in its own field, not merely that the ID
 * appears somewhere in the text. A plain-text line containing the ID would satisfy a substring check
 * while being useless to a log aggregator.
 */
@SpringBootTest(classes = StructuredLoggingTest.LoggingOnlyApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "logging.structured.format.console=ecs",
                "spring.application.name=echo-service",
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false",
                "platform.config-watcher.enabled=false"
        })
@ActiveProfiles("test")
class StructuredLoggingTest {

    @org.springframework.boot.autoconfigure.SpringBootApplication
    static class LoggingOnlyApp {
    }

    @Test
    @DisplayName("a log line is ECS JSON carrying correlationId in its own field")
    void logLineIsEcsJsonWithCorrelationId() throws Exception {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StructuredLoggingTest.class);
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        MDC.put(CorrelationIds.MDC_KEY, "log-check-123456");
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            log.info("structured logging probe");
        } finally {
            System.setOut(originalOut);
            MDC.remove(CorrelationIds.MDC_KEY);
        }

        List<String> lines = captured.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.contains("structured logging probe"))
                .toList();
        assertThat(lines).as("the probe line was captured").isNotEmpty();

        JsonNode entry = new ObjectMapper().readTree(lines.get(0));
        assertThat(entry.path("@timestamp").asText()).as("ECS timestamp").isNotEmpty();
        assertThat(entry.path("log").path("level").asText()).isEqualTo("INFO");
        assertThat(entry.path("message").asText()).isEqualTo("structured logging probe");
        assertThat(entry.path("correlationId").asText())
                .as("the correlation ID is its own field, not buried in the message")
                .isEqualTo("log-check-123456");
    }
}

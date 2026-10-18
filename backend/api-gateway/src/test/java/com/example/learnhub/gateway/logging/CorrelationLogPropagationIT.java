package com.example.learnhub.gateway.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.common.correlation.CorrelationIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Research R9 and FR-020, for a WebFlux application specifically: a servlet filter cannot carry the
 * correlation ID the way {@code CorrelationIdFilter} does for servlet services, because a reactive
 * handler can resume on a different thread than the one that started it. The gateway instead writes
 * the ID into the Reactor {@code Context} ({@code CorrelationIdGlobalFilter}) and relies on
 * Micrometer's context-propagation hook plus an explicitly registered
 * {@code Slf4jThreadLocalAccessor} ({@code GatewayConfiguration}) to put it back into MDC wherever
 * the chain happens to run.
 *
 * <p>The probe is a second {@link GlobalFilter} that delays, logs, and completes the response itself,
 * so the thread hop and the log call sit unambiguously inside the gateway's own reactive filter
 * chain — downstream of {@code CorrelationIdGlobalFilter}'s {@code contextWrite}, with no forwarded
 * dispatch or separate subscription in between whose propagation behaviour would also need proving.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.reactor.context-propagation=auto",
        "logging.structured.format.console=ecs",
        "spring.cloud.gateway.server.webflux.routes[0].id=logprobe",
        "spring.cloud.gateway.server.webflux.routes[0].uri=no://op",
        "spring.cloud.gateway.server.webflux.routes[0].order=0",
        "spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/__log-probe/entry",
        "spring.cloud.gateway.server.webflux.routes[0].metadata.public=true"
})
@Import(CorrelationLogPropagationIT.LogProbeConfig.class)
class CorrelationLogPropagationIT {

    @Autowired
    private WebTestClient client;

    @Test
    @DisplayName("a log line written on a different thread inside the reactive chain still carries the request's correlation ID")
    void logLineAcrossThreadHopCarriesCorrelationId() throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        String correlationId;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            correlationId = client.get().uri("/__log-probe/entry")
                    .header(CorrelationIds.HEADER, "log-propagation-12345")
                    .exchange()
                    .expectStatus().isOk()
                    .returnResult(String.class)
                    .getResponseHeaders()
                    .getFirst(CorrelationIds.HEADER);
        } finally {
            System.setOut(originalOut);
        }

        assertThat(correlationId).isEqualTo("log-propagation-12345");

        List<String> probeLines = captured.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.contains("probe log line, after a thread hop"))
                .toList();
        assertThat(probeLines).as("the probe's log line must have been captured").isNotEmpty();

        JsonNode entry = new ObjectMapper().readTree(probeLines.get(0));
        assertThat(entry.path("log").path("level").asText()).isEqualTo("INFO");
        assertThat(entry.path("correlationId").asText())
                .as("the ID must have followed the request across the thread hop, via Reactor context "
                        + "propagation and the registered Slf4jThreadLocalAccessor, not merely survived "
                        + "because logging happened to stay on the original thread")
                .isEqualTo(correlationId);
    }

    @TestConfiguration
    static class LogProbeConfig {
        @Bean
        LogProbeGlobalFilter logProbeGlobalFilter() {
            return new LogProbeGlobalFilter();
        }
    }

    /**
     * Delays, logs on whatever thread the delay resumes on, and completes the response itself: no
     * further routing is needed, so the probe path never touches {@code NettyRoutingFilter} or
     * {@code ForwardRoutingFilter}.
     */
    static class LogProbeGlobalFilter implements GlobalFilter, Ordered {
        private static final Logger log = LoggerFactory.getLogger(LogProbeGlobalFilter.class);

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            return Mono.delay(Duration.ofMillis(30))
                    .doOnNext(tick -> log.info("probe log line, after a thread hop"))
                    .then(Mono.defer(() -> {
                        exchange.getResponse().setStatusCode(HttpStatus.OK);
                        return exchange.getResponse().setComplete();
                    }));
        }
    }
}

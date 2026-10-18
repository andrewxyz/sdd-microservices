package com.example.learnhub.echo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * FR-017: a service that cannot reach central configuration must retry briefly and then fail with a
 * clear message, rather than starting with settings missing.
 *
 * <p>Starting half-configured is the dangerous outcome: the service would come up healthy with
 * default values and route real traffic, which is why the import is non-optional and fail-fast.
 */
class EchoFailFastIT {

    /** A port nothing is listening on, so the config client gets a connection refusal immediately. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    @DisplayName("an unreachable config server stops startup within 30 seconds, naming the server")
    void failsFastWhenConfigServerIsUnreachable() throws IOException {
        String configUrl = "http://localhost:" + closedPort();
        Instant started = Instant.now();

        SpringApplicationBuilder app = new SpringApplicationBuilder(EchoApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .properties(
                        "spring.config.import=configserver:" + configUrl,
                        "spring.cloud.config.username=config",
                        "spring.cloud.config.password=config",
                        "eureka.client.enabled=false",
                        "platform.config-watcher.enabled=false");

        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> {
            try (ConfigurableApplicationContext context = app.run()) {
                // must not get here
            }
        });

        Duration elapsed = Duration.between(started, Instant.now());

        assertThat(failure).as("startup must fail, not continue with missing settings").isNotNull();
        assertThat(elapsed).as("FR-017 allows at most 30 seconds of retrying")
                .isLessThan(Duration.ofSeconds(30));

        String message = rootMessage(failure);
        assertThat(message).as("the message must point at the config server: %s", message)
                .containsIgnoringCase("config");
    }

    @Test
    @DisplayName("the import is non-optional, so a missing config server is never shrugged off")
    void importIsNotOptional() throws Exception {
        String yaml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/application.yml"));

        assertThat(yaml).contains("configserver:");
        assertThat(yaml).as("an 'optional:' prefix would let the service start without settings")
                .doesNotContain("optional:configserver");
        assertThat(yaml).contains("fail-fast: true");
    }

    private static String rootMessage(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            all.append(current.getMessage()).append(' ');
            if (current.getCause() == current) {
                break;
            }
        }
        return all.toString();
    }
}

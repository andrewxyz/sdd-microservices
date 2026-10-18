package com.example.learnhub.gateway.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guard test for research decision R1: a {@link ContextRefresher#refresh()} really does update the
 * gateway's CORS configuration, its route table (both additions and removals) and ordinary bound
 * {@code @ConfigurationProperties}.
 *
 * <p>This is <strong>expected to PASS on first run</strong>. It pins a framework assumption that the
 * whole live-configuration design rests on: if a future Spring Cloud Gateway upgrade stops rebinding
 * these, this test fails and the documented fallback (a custom {@code CorsConfigurationSource} plus a
 * route-refresh listener) becomes necessary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("refreshit")
@Import(ConfigRefreshIT.ProbeConfig.class)
class ConfigRefreshIT {

    /** Must match spring.config.import in src/test/resources/application.yml. */
    private static final Path CONFIG = Path.of("target", "refresh-it", "config.yml");

    private static final String BEFORE = """
            probe:
              greeting: first
            spring:
              cloud:
                gateway:
                  server:
                    webflux:
                      globalcors:
                        cors-configurations:
                          '[/**]':
                            allowed-origins: ["http://a.example"]
                            allowed-methods: ["GET", "OPTIONS"]
                            allowed-headers: "*"
                      routes:
                        # Marked public because the gateway protects every route by default; this
                        # test is about refresh, not access control.
                        - id: kept
                          uri: forward:/__probe/one
                          predicates: ["Path=/api/v1/kept/**"]
                          metadata:
                            public: true
                        - id: removable
                          uri: forward:/__probe/two
                          predicates: ["Path=/api/v1/removable/**"]
                          metadata:
                            public: true
            """;

    private static final String AFTER = """
            probe:
              greeting: second
            spring:
              cloud:
                gateway:
                  server:
                    webflux:
                      globalcors:
                        cors-configurations:
                          '[/**]':
                            allowed-origins: ["http://b.example"]
                            allowed-methods: ["GET", "OPTIONS"]
                            allowed-headers: "*"
                      routes:
                        - id: kept
                          uri: forward:/__probe/one
                          predicates: ["Path=/api/v1/kept/**"]
                          metadata:
                            public: true
                        - id: added
                          uri: forward:/__probe/two
                          predicates: ["Path=/api/v1/added/**"]
                          metadata:
                            public: true
            """;

    @BeforeAll
    static void writeInitialConfig() throws Exception {
        Files.createDirectories(CONFIG.getParent());
        Files.writeString(CONFIG, BEFORE);
    }

    @Autowired
    private WebTestClient client;

    @Autowired
    private ContextRefresher refresher;

    @Autowired
    private ProbeProperties properties;

    @Test
    @DisplayName("refresh() updates CORS origins, added and removed routes, and bound properties")
    void refreshUpdatesCorsRoutesAndProperties() throws Exception {
        assertThat(preflightStatus("http://a.example")).as("allowed origin before refresh").isEqualTo(200);
        assertThat(preflightStatus("http://b.example")).as("other origin before refresh").isEqualTo(403);
        assertThat(getStatus("/api/v1/kept/x")).as("kept route before refresh").isEqualTo(200);
        assertThat(getStatus("/api/v1/removable/x")).as("removable route before refresh").isEqualTo(200);
        assertThat(getStatus("/api/v1/added/x")).as("added route before refresh").isEqualTo(404);
        assertThat(properties.getGreeting()).as("bound property before refresh").isEqualTo("first");

        Files.writeString(CONFIG, AFTER);
        refresher.refresh();

        assertThat(preflightStatus("http://a.example")).as("origin removed by refresh").isEqualTo(403);
        assertThat(preflightStatus("http://b.example")).as("origin added by refresh").isEqualTo(200);
        assertThat(getStatus("/api/v1/kept/x")).as("kept route after refresh").isEqualTo(200);
        assertThat(getStatus("/api/v1/removable/x")).as("route removed by refresh").isEqualTo(404);
        assertThat(getStatus("/api/v1/added/x")).as("route added by refresh").isEqualTo(200);
        assertThat(properties.getGreeting()).as("bound property after refresh").isEqualTo("second");
    }

    private int preflightStatus(String origin) {
        return client.method(HttpMethod.OPTIONS)
                .uri("/api/v1/kept/x")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .returnResult(String.class)
                .getStatus()
                .value();
    }

    private int getStatus(String path) {
        return client.get().uri(path).exchange().returnResult(String.class).getStatus().value();
    }

    @ConfigurationProperties("probe")
    static class ProbeProperties {
        private String greeting;

        public String getGreeting() {
            return greeting;
        }

        public void setGreeting(String greeting) {
            this.greeting = greeting;
        }
    }

    @TestConfiguration
    @EnableConfigurationProperties(ProbeProperties.class)
    static class ProbeConfig {
        @Bean
        ProbeEndpoints probeEndpoints() {
            return new ProbeEndpoints();
        }
    }

    @RestController
    static class ProbeEndpoints {
        @GetMapping("/__probe/one")
        String one() {
            return "one";
        }

        @GetMapping("/__probe/two")
        String two() {
            return "two";
        }
    }
}

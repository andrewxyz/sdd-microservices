package com.example.learnhub.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads the real {@code backend/config-repo/api-gateway.yml} that the running platform actually
 * serves, rather than a copy embedded in a test, so a change to the committed file is what this test
 * reacts to. Parsed directly with SnakeYaml (already on the classpath via Spring Boot) instead of
 * booting a Spring context: no config server or routing machinery is needed to check the file's
 * structure.
 */
class RealGatewayConfigTest {

    private static final Path CONFIG_FILE = Path.of("..", "config-repo", "api-gateway.yml");

    private static Map<String, Object> root;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws Exception {
        Assumptions.assumeTrue(Files.exists(CONFIG_FILE), "backend/config-repo/api-gateway.yml not present");
        try (var in = Files.newInputStream(CONFIG_FILE)) {
            root = new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> path(Object node, String... keys) {
        Map<String, Object> current = (Map<String, Object>) node;
        for (String key : keys) {
            Object next = current.get(key);
            assertThat(next).as("expected key '%s'", key).isNotNull();
            current = (Map<String, Object>) next;
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> routes() {
        Map<String, Object> webflux = path(root, "spring", "cloud", "gateway", "server", "webflux");
        return (List<Map<String, Object>>) webflux.get("routes");
    }

    private static Map<String, Object> routeById(String id) {
        return routes().stream()
                .filter(route -> id.equals(route.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no route with id '" + id + "'"));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("echo-public is the narrow public route, ahead of the protected catch-all")
    void echoPublicRoute() {
        Map<String, Object> route = routeById("echo-public");

        assertThat(route.get("uri")).isEqualTo("lb://echo-service");
        assertThat(route.get("order")).isEqualTo(0);
        assertThat((List<String>) route.get("predicates")).containsExactly("Path=/api/v1/echo/ping");

        Map<String, Object> metadata = (Map<String, Object>) route.get("metadata");
        assertThat(metadata).as("echo-public must declare metadata.public").isNotNull();
        assertThat(metadata.get("public")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("echo is the protected catch-all, ordered after echo-public")
    void echoCatchAllRoute() {
        Map<String, Object> route = routeById("echo");

        assertThat(route.get("uri")).isEqualTo("lb://echo-service");
        assertThat(route.get("order")).isEqualTo(1);
        assertThat((List<String>) route.get("predicates")).containsExactly("Path=/api/v1/echo/**");
        assertThat(route.get("metadata"))
                .as("no metadata.public: true here, or the route would not be protected by default")
                .satisfiesAnyOf(
                        metadata -> assertThat(metadata).isNull(),
                        metadata -> assertThat(((Map<String, Object>) metadata).get("public")).isNotEqualTo(true));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("auth-public is the narrow public route, ahead of the protected auth catch-all (specs/002-user-auth)")
    void authPublicRoute() {
        Map<String, Object> route = routeById("auth-public");

        assertThat(route.get("uri")).isEqualTo("lb://auth-service");
        Map<String, Object> metadata = (Map<String, Object>) route.get("metadata");
        assertThat(metadata).as("auth-public must declare metadata.public").isNotNull();
        assertThat(metadata.get("public")).isEqualTo(true);

        Map<String, Object> catchAll = routeById("auth");
        assertThat((Integer) route.get("order")).as("auth-public must be ordered before the auth catch-all")
                .isLessThan((Integer) catchAll.get("order"));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("auth-admin restricts role changes to ADMINISTRATOR, ahead of the auth catch-all")
    void authAdminRoute() {
        Map<String, Object> route = routeById("auth-admin");

        Map<String, Object> metadata = (Map<String, Object>) route.get("metadata");
        assertThat(metadata).as("auth-admin must declare metadata.roles").isNotNull();
        assertThat((List<String>) metadata.get("roles")).containsExactly("ADMINISTRATOR");

        Map<String, Object> catchAll = routeById("auth");
        assertThat((Integer) route.get("order")).as("auth-admin must be ordered before the auth catch-all")
                .isLessThan((Integer) catchAll.get("order"));
    }

    @Test
    @DisplayName("connect and response timeouts match the research R8 decision")
    void timeouts() {
        Map<String, Object> httpclient = path(root, "spring", "cloud", "gateway", "server", "webflux")
                .get("httpclient") instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        assertThat(httpclient).as("httpclient settings must be present").isNotNull();

        assertThat(httpclient.get("connect-timeout")).isEqualTo(2000);
        assertThat(String.valueOf(httpclient.get("response-timeout"))).isEqualTo("5s");
    }

    @Test
    @DisplayName("allowed CORS origins and rate limits are present")
    void corsAndRateLimitsPresent() {
        Map<String, Object> webflux = path(root, "spring", "cloud", "gateway", "server", "webflux");
        Map<String, Object> globalcors = (Map<String, Object>) webflux.get("globalcors");
        assertThat(globalcors).as("globalcors must be configured").isNotNull();

        Map<String, Object> corsConfigurations = (Map<String, Object>) globalcors.get("cors-configurations");
        assertThat(corsConfigurations).isNotEmpty();
        Map<String, Object> allPaths = (Map<String, Object>) corsConfigurations.values().iterator().next();
        assertThat((List<?>) allPaths.get("allowed-origins")).as("allowed origins must be listed").isNotEmpty();

        Map<String, Object> rateLimit = path(root, "platform", "gateway", "rate-limit");
        assertThat(rateLimit.get("capacity")).isNotNull();
        assertThat(rateLimit.get("refill-per-second")).isNotNull();
    }

    @Test
    @DisplayName("no route URI carries a literal host or port")
    void noHostOrPortInAnyRouteUri() {
        for (Map<String, Object> route : routes()) {
            String id = String.valueOf(route.get("id"));
            String uri = String.valueOf(route.get("uri"));

            assertThat(uri).as("route '%s' must use lb:// so it never hardcodes a host", id)
                    .startsWith("lb://");
            assertThat(uri).as("route '%s' uri '%s' must not pin a port", id, uri)
                    .doesNotContainPattern(":\\d+");
        }
    }

    @Test
    @DisplayName("the file actually declares at least the two echo routes this test expects")
    void fileIsNotEmpty() {
        assertThat(routes()).extracting(route -> route.get("id"))
                .contains("echo-public", "echo");
    }
}

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
    @SuppressWarnings("unchecked")
    @DisplayName("courses-public-list and courses-public-detail are public, ahead of the protected courses-manage catch-all (specs/003-course-catalog)")
    void coursesPublicRoutes() {
        Map<String, Object> list = routeById("courses-public-list");
        Map<String, Object> detail = routeById("courses-public-detail");
        Map<String, Object> catchAll = routeById("courses-manage");

        assertThat(list.get("uri")).isEqualTo("lb://course-service");
        assertThat((Map<String, Object>) list.get("metadata")).containsEntry("public", true);
        assertThat((List<String>) list.get("predicates")).contains("Method=GET");

        assertThat((List<String>) detail.get("predicates")).containsExactly("Path=/api/v1/courses/*", "Method=GET");
        assertThat((Map<String, Object>) detail.get("metadata")).containsEntry("public", true);

        assertThat((Integer) list.get("order")).isLessThan((Integer) catchAll.get("order"));
        assertThat((Integer) detail.get("order")).isLessThan((Integer) catchAll.get("order"));
        assertThat(catchAll.get("metadata")).as("courses-manage must not be public").isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("categories-admin restricts mutation to ADMINISTRATOR, ahead of no catch-all needed (specs/003-course-catalog)")
    void categoriesAdminRoute() {
        Map<String, Object> adminRoute = routeById("categories-admin");
        Map<String, Object> publicList = routeById("categories-public-list");

        Map<String, Object> metadata = (Map<String, Object>) adminRoute.get("metadata");
        assertThat(metadata).as("categories-admin must declare metadata.roles").isNotNull();
        assertThat((List<String>) metadata.get("roles")).containsExactly("ADMINISTRATOR");

        assertThat((Integer) publicList.get("order")).isLessThan((Integer) adminRoute.get("order"));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("my-courses has no overlap with the public single-course-detail route (specs/003-course-catalog research.md R2)")
    void myCoursesRouteIsStructurallyDistinct() {
        Map<String, Object> myCourses = routeById("my-courses");

        assertThat((List<String>) myCourses.get("predicates")).containsExactly("Path=/api/v1/my/courses");
        assertThat(myCourses.get("metadata")).as("my-courses must not be public").isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("checkout, payments, enrollments and course-content are all protected, with no roles metadata (specs/004-checkout-enrollment research.md R7)")
    void checkoutEnrollmentRoutes() {
        Map<String, Object> checkout = routeById("checkout");
        Map<String, Object> payments = routeById("payments");
        Map<String, Object> enrollments = routeById("enrollments");
        Map<String, Object> content = routeById("course-content");
        Map<String, Object> catchAll = routeById("courses-manage");

        assertThat(checkout.get("uri")).isEqualTo("lb://payment-service");
        assertThat((List<String>) checkout.get("predicates")).contains("Method=POST");
        assertThat(payments.get("uri")).isEqualTo("lb://payment-service");
        assertThat(enrollments.get("uri")).isEqualTo("lb://enrollment-service");
        assertThat(content.get("uri")).isEqualTo("lb://course-service");

        for (Map<String, Object> route : List.of(checkout, payments, enrollments, content)) {
            assertThat(route.get("metadata"))
                    .as("%s must not be public", route.get("id"))
                    .satisfiesAnyOf(
                            metadata -> assertThat(metadata).isNull(),
                            metadata -> assertThat(((Map<String, Object>) metadata).get("public")).isNotEqualTo(true));
            assertThat(route.get("metadata"))
                    .as("%s must not carry roles metadata (research.md R7)", route.get("id"))
                    .satisfiesAnyOf(
                            metadata -> assertThat(metadata).isNull(),
                            metadata -> assertThat((Map<String, Object>) metadata).doesNotContainKey("roles"));
        }

        assertThat((Integer) content.get("order")).as("course-content must be ordered before courses-manage")
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

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("certificates-mine is protected; certificates-verify is public with the stricter rate limit (specs/005-progress-certificates-notifications research.md R8, R9)")
    void certificateRoutes() {
        Map<String, Object> mine = routeById("certificates-mine");
        Map<String, Object> verify = routeById("certificates-verify");

        assertThat(mine.get("uri")).isEqualTo("lb://certificate-service");
        assertThat(mine.get("metadata"))
                .as("certificates-mine must not be public")
                .satisfiesAnyOf(
                        metadata -> assertThat(metadata).isNull(),
                        metadata -> assertThat(((Map<String, Object>) metadata).get("public")).isNotEqualTo(true));

        assertThat(verify.get("uri")).isEqualTo("lb://certificate-service");
        Map<String, Object> verifyMetadata = (Map<String, Object>) verify.get("metadata");
        assertThat(verifyMetadata).as("certificates-verify must declare metadata").isNotNull();
        assertThat(verifyMetadata.get("public")).isEqualTo(true);
        assertThat(verifyMetadata.get("rate-limit")).isEqualTo("strict");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("the enrollments route covers the new progress/completion sub-paths, not just the exact list path (research.md R9 implementation finding)")
    void enrollmentsRouteCoversProgressSubPaths() {
        Map<String, Object> route = routeById("enrollments");

        assertThat((List<String>) route.get("predicates"))
                .as("the enrollments route must be a catch-all, not an exact-path match, or progress/completion requests 404 before reaching enrollment-service")
                .containsExactly("Path=/api/v1/enrollments/**");
    }

    @Test
    @DisplayName("stricter rate limit settings are present")
    void strictRateLimitPresent() {
        Map<String, Object> rateLimitStrict = path(root, "platform", "gateway", "rate-limit-strict");
        assertThat(rateLimitStrict.get("capacity")).isNotNull();
        assertThat(rateLimitStrict.get("refill-per-second")).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("no route targets /api/v1/internal/** on any service — the brief's explicit assertion (specs/005-progress-certificates-notifications)")
    void noRouteEverTargetsInternalPaths() {
        for (Map<String, Object> route : routes()) {
            List<String> predicates = (List<String>) route.get("predicates");
            if (predicates == null) {
                continue;
            }
            for (String predicate : predicates) {
                assertThat(predicate)
                        .as("route '%s' predicate '%s' must never target /api/v1/internal/**",
                                route.get("id"), predicate)
                        .doesNotContain("/internal/");
            }
        }
    }
}

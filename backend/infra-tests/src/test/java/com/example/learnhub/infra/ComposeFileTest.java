package com.example.learnhub.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Static checks on the repo-root Compose file: health checks, startup order, the constitution's port
 * table, persistent volumes and required settings. Catching these here means a typo fails in seconds
 * rather than after a three-minute stack start.
 */
class ComposeFileTest {

    private static final Path COMPOSE = Path.of("..", "..", "docker-compose.yml");

    private static Map<String, Object> services;
    private static Map<String, Object> volumes;
    private static String raw;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws Exception {
        Assumptions.assumeTrue(Files.exists(COMPOSE), "docker-compose.yml not present yet");
        raw = Files.readString(COMPOSE);
        Map<String, Object> root = new Yaml().load(raw);
        services = (Map<String, Object>) root.get("services");
        volumes = (Map<String, Object>) root.get("volumes");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) {
        assertThat(services).as("service %s is declared", name).containsKey(name);
        return (Map<String, Object>) services.get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<String> ports(String name) {
        Object ports = service(name).get("ports");
        return ports == null ? List.of() : (List<String>) ports;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dependsOn(String name) {
        Object depends = service(name).get("depends_on");
        return depends == null ? Map.of() : (Map<String, Object>) depends;
    }

    @Test
    @DisplayName("every long-running service has a health check")
    void everyLongRunningServiceHasHealthcheck() {
        for (String name : services.keySet()) {
            if (name.equals("db-init")) {
                continue; // one-shot: it completes rather than becoming healthy
            }
            assertThat(service(name)).as("%s has a healthcheck", name).containsKey("healthcheck");
        }
    }

    @Test
    @DisplayName("host ports follow the constitution's port table")
    void hostPortsMatchTheConstitution() {
        assertThat(ports("discovery-server")).contains("9761:9761");
        assertThat(ports("config-server")).contains("9888:9888");
        assertThat(ports("echo-service")).contains("9099:9099");
        // Third-party images keep their internal ports and are mapped into the 9xxx range.
        assertThat(ports("postgres")).contains("9432:5432");
        assertThat(ports("rabbitmq")).contains("9672:5672", "9673:15672");
        assertThat(ports("mailpit")).contains("9025:1025", "9825:8025");
        // 010-observability: Prometheus keeps its own default port; Grafana's 3000 is mapped in.
        assertThat(ports("prometheus")).contains("9090:9090");
        assertThat(ports("grafana")).contains("9030:3000");
    }

    @Test
    @DisplayName("no service binds a port reserved for a future business service (9001-9007)")
    void reservedPortsAreNotUsed() {
        // 9001 is auth-service's real port (specs/002-user-auth); 9002 is course-service's real port
        // (specs/003-course-catalog); 9003 is enrollment-service's and 9004 is payment-service's real
        // port (specs/004-checkout-enrollment); 9005 is certificate-service's and 9007 is
        // notification-service's real port (specs/005-progress-certificates-notifications); 9006 is
        // review-service's real port (specs/007-review-service).
        for (String name : services.keySet()) {
            for (String mapping : ports(name)) {
                int hostPort = Integer.parseInt(mapping.split(":")[0].replace("\"", "").trim());
                boolean isAuthServiceOnItsOwnPort = hostPort == 9001 && "auth-service".equals(name);
                boolean isCourseServiceOnItsOwnPort = hostPort == 9002 && "course-service".equals(name);
                boolean isEnrollmentServiceOnItsOwnPort = hostPort == 9003 && "enrollment-service".equals(name);
                boolean isPaymentServiceOnItsOwnPort = hostPort == 9004 && "payment-service".equals(name);
                boolean isCertificateServiceOnItsOwnPort = hostPort == 9005 && "certificate-service".equals(name);
                boolean isReviewServiceOnItsOwnPort = hostPort == 9006 && "review-service".equals(name);
                boolean isNotificationServiceOnItsOwnPort = hostPort == 9007 && "notification-service".equals(name);
                assertThat(isAuthServiceOnItsOwnPort || isCourseServiceOnItsOwnPort
                                || isEnrollmentServiceOnItsOwnPort || isPaymentServiceOnItsOwnPort
                                || isCertificateServiceOnItsOwnPort || isReviewServiceOnItsOwnPort
                                || isNotificationServiceOnItsOwnPort
                                || hostPort < 9003 || hostPort > 9007)
                        .as("%s uses port %d, which is reserved for a future business service",
                                name, hostPort)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("database and broker data live in named volumes so a restart keeps them")
    void dataUsesNamedVolumes() {
        assertThat(volumes).as("named volumes are declared")
                .containsKeys("postgres-data", "rabbitmq-data", "prometheus-data", "grafana-data");
        assertThat(service("postgres").get("volumes").toString()).contains("postgres-data");
        assertThat(service("rabbitmq").get("volumes").toString()).contains("rabbitmq-data");
        assertThat(service("prometheus").get("volumes").toString()).contains("prometheus-data");
        assertThat(service("grafana").get("volumes").toString()).contains("grafana-data");
    }

    @Test
    @DisplayName("the monitoring stack is capped, bounded and cannot rewrite its own configuration")
    void monitoringStackIsConstrained() {
        // Memory caps: the dev host runs this whole stack in under 4 GB, and SC-006 allows the two
        // monitoring containers 500 MB between them.
        assertThat(service("prometheus").get("mem_limit")).as("prometheus is capped").isNotNull();
        assertThat(service("grafana").get("mem_limit")).as("grafana is capped").isNotNull();

        // Retention is bounded on both axes: time alone leaves size unbounded if scrape volume
        // grows, and SC-008 asks for a size that stays bounded indefinitely.
        String prometheusCommand = String.valueOf(service("prometheus").get("command"));
        assertThat(prometheusCommand)
                .contains("--storage.tsdb.retention.time=")
                .contains("--storage.tsdb.retention.size=");

        // Provisioned configuration is mounted read-only, so a hand edit in either tool's UI cannot
        // become the source of truth (FR-011, FR-016) - the same guarantee config-server gets.
        assertThat(service("prometheus").get("volumes").toString())
                .as("prometheus config is mounted read-only").contains(":ro");
        assertThat(service("grafana").get("volumes").toString())
                .as("grafana provisioning is mounted read-only").contains(":ro");

        // Grafana must not start against a datasource that is not answering yet.
        assertThat(dependsOn("grafana")).containsKey("prometheus");
        assertThat(dependsOn("grafana").get("prometheus").toString()).contains("service_healthy");
    }

    @Test
    @DisplayName("Grafana credentials come from the environment with no default")
    void grafanaCredentialsAreRequiredSettings() {
        // FR-012: no default and no blank password. The ${VAR:?message} form also rejects an empty
        // value, so a present-but-empty setting fails at start rather than silently using a default.
        assertThat(raw).contains("${GRAFANA_ADMIN_USER:?").contains("${GRAFANA_ADMIN_PASSWORD:?");
    }

    @Test
    @DisplayName("startup order is discovery, then config, then the services that depend on them")
    void startupOrder() {
        assertThat(dependsOn("config-server")).containsKey("discovery-server");
        assertThat(dependsOn("config-server").get("discovery-server").toString())
                .contains("service_healthy");

        assertThat(dependsOn("echo-service")).containsKeys("discovery-server", "config-server");
        assertThat(dependsOn("echo-service").get("config-server").toString()).contains("service_healthy");
    }

    @Test
    @DisplayName("db-init waits for a healthy database")
    void dbInitWaitsForPostgres() {
        assertThat(dependsOn("db-init")).containsKey("postgres");
        assertThat(dependsOn("db-init").get("postgres").toString()).contains("service_healthy");
    }

    @Test
    @DisplayName("the config repo is mounted read-only, so a service cannot rewrite settings")
    void configRepoMountedReadOnly() {
        assertThat(service("config-server").get("volumes").toString())
                .contains("config-repo").contains(":ro");
    }

    @Test
    @DisplayName("every required setting uses the ${VAR:?message} form, which also rejects empty values")
    void requiredSettingsFailFast() {
        List<String> required = List.of(
                "POSTGRES_PASSWORD", "RABBITMQ_DEFAULT_PASS", "EUREKA_PASSWORD", "CONFIG_PASSWORD",
                "AUTH_DB_PASSWORD", "COURSE_DB_PASSWORD", "ENROLLMENT_DB_PASSWORD", "PAYMENT_DB_PASSWORD",
                "CERTIFICATE_DB_PASSWORD", "REVIEW_DB_PASSWORD", "NOTIFICATION_DB_PASSWORD", "ANALYTICS_DB_PASSWORD");

        for (String variable : required) {
            assertThat(raw).as("%s must use the ${VAR:?message} form", variable)
                    .contains("${" + variable + ":?");
        }
    }

    @Test
    @DisplayName("the gateway is published on 9080 and waits for discovery and config")
    void gatewayIsWiredCorrectly() {
        assertThat(ports("api-gateway")).contains("9080:9080");
        assertThat(service("api-gateway").get("build").toString()).contains("api-gateway");
        assertThat(dependsOn("api-gateway")).containsKeys("discovery-server", "config-server");
        assertThat(service("api-gateway")).containsKey("healthcheck");
    }

    @Test
    @DisplayName("the second echo copy is opt-in, shares the image and publishes no host port")
    void secondEchoCopyIsScaleOnly() {
        Map<String, Object> second = service("echo-service-b");

        assertThat(second.get("profiles").toString()).contains("scale");
        assertThat(second.get("build").toString())
                .as("same image as echo-service, so it is a genuine second copy")
                .contains("echo-service");
        assertThat(second.get("hostname")).isEqualTo("echo-service-b");
        assertThat(ports("echo-service-b")).as("reached only through the gateway").isEmpty();
        assertThat(second).containsKey("healthcheck");
        assertThat(dependsOn("echo-service-b")).containsKeys("discovery-server", "config-server");
    }

    @Test
    @DisplayName("Mailpit has no relay configured, so local mail never leaves the machine")
    void mailpitDoesNotRelay() {
        String mailpit = service("mailpit").toString();

        assertThat(mailpit).doesNotContain("MP_SMTP_RELAY");
        assertThat(mailpit).doesNotContain("relay");
    }
}

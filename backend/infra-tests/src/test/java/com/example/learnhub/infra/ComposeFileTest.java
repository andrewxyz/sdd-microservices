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
    }

    @Test
    @DisplayName("no service binds a port reserved for a future business service (9001-9007)")
    void reservedPortsAreNotUsed() {
        for (String name : services.keySet()) {
            for (String mapping : ports(name)) {
                int hostPort = Integer.parseInt(mapping.split(":")[0].replace("\"", "").trim());
                assertThat(hostPort).as("%s must not use a reserved business port", name)
                        .isNotBetween(9001, 9007);
            }
        }
    }

    @Test
    @DisplayName("database and broker data live in named volumes so a restart keeps them")
    void dataUsesNamedVolumes() {
        assertThat(volumes).as("named volumes are declared")
                .containsKeys("postgres-data", "rabbitmq-data");
        assertThat(service("postgres").get("volumes").toString()).contains("postgres-data");
        assertThat(service("rabbitmq").get("volumes").toString()).contains("rabbitmq-data");
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
                "CERTIFICATE_DB_PASSWORD", "REVIEW_DB_PASSWORD", "NOTIFICATION_DB_PASSWORD");

        for (String variable : required) {
            assertThat(raw).as("%s must use the ${VAR:?message} form", variable)
                    .contains("${" + variable + ":?");
        }
    }

    @Test
    @DisplayName("Mailpit has no relay configured, so local mail never leaves the machine")
    void mailpitDoesNotRelay() {
        String mailpit = service("mailpit").toString();

        assertThat(mailpit).doesNotContain("MP_SMTP_RELAY");
        assertThat(mailpit).doesNotContain("relay");
    }
}

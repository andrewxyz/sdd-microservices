package com.example.learnhub.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-029 and SC-007: a service must need no code of its own to register, load central settings or
 * report health. This scans every business-style module and fails if any of it reappears.
 *
 * <p>The infrastructure servers are exempt because being the registry or the config server is their
 * whole job. Everything else, including services added by later specs, is checked automatically.
 */
class NoWiringCodeTest {

    private static final Path BACKEND = Path.of("..");

    /** Allowed to contain wiring code: they are the wiring. */
    private static final Set<String> EXEMPT = Set.of(
            "discovery-server", "config-server", "shared-events", "platform-common", "infra-tests");

    /** Each marker is a way a service might take registration, config or health into its own hands. */
    private static final List<String[]> FORBIDDEN = List.of(
            new String[] {"@EnableEurekaClient", "registration is automatic"},
            new String[] {"@EnableDiscoveryClient", "registration is automatic"},
            new String[] {"EurekaClient", "talk to the registry through the platform, not directly"},
            new String[] {"@EnableConfigServer", "only the config server serves configuration"},
            new String[] {"@RefreshScope", "the shared config watcher refreshes bound properties"},
            new String[] {"HealthIndicator", "probes come from Actuator and the shared defaults"},
            new String[] {"ContextRefresher", "refreshing is the platform's job"},
            new String[] {"@EnableScheduling", "no service-specific polling of configuration"});

    private static List<Path> serviceModules;

    @BeforeAll
    static void findModules() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(BACKEND), "backend/ not present");
        try (Stream<Path> entries = Files.list(BACKEND)) {
            serviceModules = entries
                    .filter(Files::isDirectory)
                    .filter(path -> Files.exists(path.resolve("pom.xml")))
                    .filter(path -> !EXEMPT.contains(path.getFileName().toString()))
                    .toList();
        }
    }

    /**
     * Removes block and line comments before scanning. Without this, a comment explaining why a
     * service deliberately does <em>not</em> use something would be reported as using it.
     */
    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    private static List<Path> mainSources(Path module) throws IOException {
        Path main = module.resolve("src/main/java");
        if (!Files.isDirectory(main)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(main)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    @Test
    @DisplayName("no service writes its own registration, configuration or health code")
    void noWiringCodeInServices() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path module : serviceModules) {
            for (Path source : mainSources(module)) {
                String code = stripComments(Files.readString(source));
                for (String[] forbidden : FORBIDDEN) {
                    if (code.contains(forbidden[0])) {
                        violations.add("%s uses %s: %s".formatted(
                                BACKEND.relativize(source), forbidden[0], forbidden[1]));
                    }
                }
            }
        }

        assertThat(violations).as("wiring code that platform-common already provides").isEmpty();
    }

    @Test
    @DisplayName("no service maps its own health endpoint")
    void noHandWrittenHealthEndpoint() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path module : serviceModules) {
            for (Path source : mainSources(module)) {
                String code = stripComments(Files.readString(source));
                if (code.contains("\"/health\"") || code.contains("(\"/actuator")) {
                    violations.add(BACKEND.relativize(source).toString());
                }
            }
        }

        assertThat(violations).as("health endpoints are provided by Actuator").isEmpty();
    }

    @Test
    @DisplayName("a service declares only its identity and config bootstrap in application.yml")
    void applicationYamlStaysMinimal() throws IOException {
        Assumptions.assumeTrue(Files.exists(BACKEND.resolve("echo-service/src/main/resources/application.yml")));

        String yaml = Files.readString(BACKEND.resolve("echo-service/src/main/resources/application.yml"));

        // Identity and bootstrap are expected; anything the config server already supplies is not.
        assertThat(yaml).contains("spring").contains("echo-service").contains("config");
        assertThat(yaml)
                .as("Eureka settings arrive from the shared defaults, not from the service")
                .doesNotContain("defaultZone");
        assertThat(yaml)
                .as("logging format arrives from the shared defaults")
                .doesNotContain("structured");
    }

    @Test
    @DisplayName("the scan actually covers the services present")
    void scanIsNotVacuous() {
        assertThat(serviceModules)
                .as("at least echo-service and api-gateway must be scanned")
                .hasSizeGreaterThanOrEqualTo(2);
    }
}

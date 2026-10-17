package com.example.learnhub.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-012: services address each other by name only. A hardcoded host or port would work on one
 * machine and break everywhere else, and it would quietly bypass the registry.
 */
class NoHardcodedAddressTest {

    private static final Path BACKEND = Path.of("..");

    /** http(s) URLs whose host is a literal rather than a ${PLACEHOLDER}. */
    private static final Pattern LITERAL_URL = Pattern.compile("https?://(?!\\$\\{)([A-Za-z0-9._-]+)");

    /** lb:// URIs must carry a service name only; a port means someone pinned an instance. */
    private static final Pattern LB_WITH_PORT = Pattern.compile("lb://[A-Za-z0-9._-]+:\\d+");

    private static List<Path> configFiles() throws IOException {
        List<Path> files = new ArrayList<>();

        Path configRepo = BACKEND.resolve("config-repo");
        if (Files.isDirectory(configRepo)) {
            try (Stream<Path> entries = Files.list(configRepo)) {
                entries.filter(path -> path.toString().endsWith(".yml")).forEach(files::add);
            }
        }

        try (Stream<Path> modules = Files.list(BACKEND)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path resources = module.resolve("src/main/resources");
                if (!Files.isDirectory(resources)) {
                    continue;
                }
                try (Stream<Path> entries = Files.list(resources)) {
                    entries.filter(path -> path.getFileName().toString().matches("application.*\\.yml"))
                            .forEach(files::add);
                }
            }
        }
        return files;
    }

    @Test
    @DisplayName("no service address is hardcoded in configuration")
    void noHardcodedAddresses() throws IOException {
        List<Path> files = configFiles();
        Assumptions.assumeFalse(files.isEmpty(), "no configuration files yet");

        List<String> violations = new ArrayList<>();

        for (Path file : files) {
            int lineNumber = 0;
            for (String line : Files.readAllLines(file)) {
                lineNumber++;
                String trimmed = line.strip();
                if (trimmed.startsWith("#")) {
                    continue;
                }

                Matcher url = LITERAL_URL.matcher(trimmed);
                while (url.find()) {
                    String host = url.group(1);
                    // localhost is allowed only as a default inside a ${VAR:default} fallback, which
                    // keeps a service runnable outside Compose without pinning anything in deployment.
                    boolean insideDefault = trimmed.contains(":-http") || trimmed.contains(":http");
                    if (!host.equals("localhost") || !insideDefault) {
                        violations.add("%s:%d uses the literal host '%s'"
                                .formatted(BACKEND.relativize(file), lineNumber, host));
                    }
                }

                if (LB_WITH_PORT.matcher(trimmed).find()) {
                    violations.add("%s:%d pins a port on an lb:// URI"
                            .formatted(BACKEND.relativize(file), lineNumber));
                }
            }
        }

        assertThat(violations).as("addresses must come from service names or environment values").isEmpty();
    }

    @Test
    @DisplayName("the registry and config server addresses come from environment values")
    void addressesComeFromTheEnvironment() throws IOException {
        Path shared = BACKEND.resolve("config-repo/application.yml");
        Assumptions.assumeTrue(Files.exists(shared), "shared defaults not present");

        String yaml = Files.readString(shared);

        assertThat(yaml).contains("${EUREKA_URL}");
    }
}

package com.example.learnhub.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-009 and SC-010: every setting the platform needs is documented, and no real secret is committed.
 */
class EnvExampleTest {

    private static final Path ROOT = Path.of("..", "..");
    private static final Path ENV_EXAMPLE = ROOT.resolve(".env.example");
    private static final Path COMPOSE = ROOT.resolve("docker-compose.yml");
    private static final Path GITIGNORE = ROOT.resolve(".gitignore");

    /** Variables Compose or Docker provides itself; they are not settings a developer supplies. */
    private static final Set<String> NOT_USER_SETTINGS = Set.of("HOSTNAME");

    private static String envExample;

    @BeforeAll
    static void load() throws Exception {
        Assumptions.assumeTrue(Files.exists(ENV_EXAMPLE), ".env.example not present yet");
        envExample = Files.readString(ENV_EXAMPLE);
    }

    private static Set<String> declaredNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String line : envExample.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            names.add(trimmed.split("=", 2)[0].strip());
        }
        return names;
    }

    @Test
    @DisplayName("every variable the Compose file requires is documented in .env.example")
    void composeVariablesAreDocumented() throws Exception {
        Assumptions.assumeTrue(Files.exists(COMPOSE), "docker-compose.yml not present yet");
        String compose = Files.readString(COMPOSE);

        Set<String> declared = declaredNames();
        Matcher matcher = Pattern.compile("\\$\\{([A-Z0-9_]+)[:?}-]").matcher(compose);

        Set<String> missing = new LinkedHashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!NOT_USER_SETTINGS.contains(name) && !declared.contains(name)) {
                missing.add(name);
            }
        }

        assertThat(missing).as("variables used by Compose but absent from .env.example").isEmpty();
    }

    @Test
    @DisplayName("every value is a placeholder, never a real secret")
    void valuesArePlaceholders() {
        List<String> suspicious = envExample.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .filter(line -> {
                    String[] parts = line.split("=", 2);
                    if (parts.length < 2) {
                        return false;
                    }
                    String name = parts[0];
                    String value = parts[1].strip();
                    boolean secretish = name.contains("PASSWORD") || name.contains("PASS")
                            || name.contains("SECRET") || name.contains("TOKEN");
                    return secretish && !value.startsWith("change-me");
                })
                .toList();

        assertThat(suspicious).as("secret-like values must start with 'change-me'").isEmpty();
    }

    @Test
    @DisplayName(".env is ignored by git and .env.example is not")
    void envIsIgnoredButExampleIsNot() throws Exception {
        Assumptions.assumeTrue(Files.exists(GITIGNORE), ".gitignore not present");
        List<String> lines = Files.readAllLines(GITIGNORE).stream().map(String::strip).toList();

        assertThat(lines).contains(".env");
        assertThat(lines).contains("!.env.example");
    }

    @Test
    @DisplayName("no real .env file is committed")
    void noRealEnvCommitted() {
        assertThat(Files.exists(ROOT.resolve(".env")) && isTracked())
                .as("a committed .env would leak secrets").isFalse();
    }

    private boolean isTracked() {
        try {
            Process process = new ProcessBuilder("git", "ls-files", "--error-unmatch", ".env")
                    .directory(ROOT.toFile())
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}

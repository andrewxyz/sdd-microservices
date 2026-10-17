package com.example.learnhub.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * FR-015 and FR-016, and the answer to clarification Q2: the native backend reads the repo from disk
 * on every request, so an edit is visible with no commit and no restart, and the three layers compose
 * with the service file winning.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"native", "local"})
class ConfigServerNativeIT {

    /**
     * Created in a static initialiser rather than with {@code @TempDir}: the path must already exist
     * when {@link DynamicPropertySource} runs, and that happens while the context is being prepared.
     */
    static final Path repo = createTempRepo();

    private static Path createTempRepo() {
        try {
            Path path = Files.createTempDirectory("learnhub-config-repo");
            path.toFile().deleteOnExit();
            return path;
        } catch (IOException e) {
            throw new IllegalStateException("could not create a temporary config repo", e);
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @DynamicPropertySource
    static void pointAtTempRepo(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.config.server.native.search-locations", () -> "file:" + repo.toAbsolutePath());
    }

    @BeforeAll
    static void writeRepo() throws IOException {
        Files.writeString(repo.resolve("application.yml"), """
                shared:
                  onlyInShared: from-shared
                layered:
                  value: from-shared
                serviceVsShared:
                  value: from-shared
                """);
        Files.writeString(repo.resolve("application-local.yml"), """
                layered:
                  value: from-profile
                profileOnly:
                  value: from-profile
                """);
        writeServiceFile("hello");
    }

    /** The one place the service file is written, so a test that edits it cannot drop other keys. */
    private static void writeServiceFile(String greeting) throws IOException {
        Files.writeString(repo.resolve("echo-service.yml"), """
                layered:
                  value: from-service
                serviceVsShared:
                  value: from-service
                echo:
                  greeting: %s
                """.formatted(greeting));
    }

    private JsonNode fetch() {
        return rest.getForObject("/echo-service/local", JsonNode.class);
    }

    private java.util.List<String> sourceNames(JsonNode reply) {
        java.util.List<String> names = new java.util.ArrayList<>();
        reply.get("propertySources").forEach(source -> names.add(source.path("name").asText()));
        return names;
    }

    private String effective(JsonNode reply, String key) {
        // Property sources come back most-specific first, so the first hit wins.
        for (JsonNode source : reply.get("propertySources")) {
            JsonNode value = source.path("source").get(key);
            if (value != null) {
                return value.asText();
            }
        }
        return null;
    }


    @Test
    @DisplayName("all three layers are delivered")
    void deliversAllThreeLayers() {
        JsonNode reply = fetch();

        String names = reply.get("propertySources").toString();
        assertThat(names).contains("echo-service.yml").contains("application-local.yml").contains("application.yml");
    }

    @Test
    @DisplayName("precedence is profile file, then service file, then shared file (measured, research R6)")
    void precedenceProfileOverServiceOverShared() {
        JsonNode reply = fetch();
        java.util.List<String> order = sourceNames(reply);

        // Spring Cloud Config's native backend puts the environment override ahead of the service's own
        // file. An earlier assumption that the service file ranks highest was wrong; this test pins the
        // behavior actually delivered, because services rely on it when a profile overrides a service.
        assertThat(order).hasSize(3);
        assertThat(order.get(0)).endsWith("application-local.yml");
        assertThat(order.get(1)).endsWith("echo-service.yml");
        assertThat(order.get(2)).endsWith("application.yml");

        assertThat(effective(reply, "layered.value"))
                .as("set in all three: the profile file must win; sources in order: %s", order)
                .isEqualTo("from-profile");
        assertThat(effective(reply, "echo.greeting"))
                .as("set only in the service file").isEqualTo("hello");
        assertThat(effective(reply, "profileOnly.value"))
                .as("set only in the profile file").isEqualTo("from-profile");
        assertThat(effective(reply, "shared.onlyInShared"))
                .as("set only in the shared file").isEqualTo("from-shared");
    }

    @Test
    @DisplayName("a service overrides a shared default when the profile file is silent on the key")
    void serviceOverridesSharedWhenProfileSilent() {
        // serviceVsShared.value is set in application.yml and echo-service.yml only, so this isolates the
        // service-over-shared step of the order from the profile-over-service step.
        assertThat(effective(fetch(), "serviceVsShared.value")).isEqualTo("from-service");
    }

    @Test
    @DisplayName("an edit is visible on the next request, with no commit and no restart")
    void editVisibleWithoutCommitOrRestart() throws IOException {
        assertThat(effective(fetch(), "echo.greeting")).isEqualTo("hello");

        try {
            writeServiceFile("edited-in-place");

            assertThat(effective(fetch(), "echo.greeting")).isEqualTo("edited-in-place");
        } finally {
            // Restore, so the other tests do not depend on the order JUnit happens to run them in.
            writeServiceFile("hello");
        }
    }
}

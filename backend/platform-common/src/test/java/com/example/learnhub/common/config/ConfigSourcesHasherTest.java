package com.example.learnhub.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The watcher decides whether to refresh by comparing hashes of the config server's reply, so the
 * hash must ignore everything that changes without the settings changing.
 */
class ConfigSourcesHasherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BASE = """
            {
              "name": "echo-service",
              "profiles": ["local"],
              "version": "abc123",
              "state": null,
              "propertySources": [
                {
                  "name": "file:/config-repo/echo-service.yml",
                  "source": {"echo.greeting": "hello", "server.port": 9099}
                },
                {
                  "name": "file:/config-repo/application.yml",
                  "source": {"management.endpoint.health.probes.enabled": true}
                }
              ]
            }
            """;

    private String hash(String json) throws Exception {
        return ConfigSourcesHasher.hash(MAPPER.readTree(json));
    }

    @Test
    @DisplayName("identical replies hash the same")
    void stableForIdenticalInput() throws Exception {
        assertThat(hash(BASE)).isEqualTo(hash(BASE));
    }

    @Test
    @DisplayName("key order inside a property source does not change the hash")
    void keyOrderIrrelevant() throws Exception {
        String reordered = BASE.replace(
                "{\"echo.greeting\": \"hello\", \"server.port\": 9099}",
                "{\"server.port\": 9099, \"echo.greeting\": \"hello\"}");

        assertThat(hash(reordered)).isEqualTo(hash(BASE));
    }

    @Test
    @DisplayName("the version and state fields are ignored")
    void versionAndStateIgnored() throws Exception {
        String different = BASE
                .replace("\"version\": \"abc123\"", "\"version\": \"totally-different-commit\"")
                .replace("\"state\": null", "\"state\": \"something\"");

        assertThat(hash(different)).isEqualTo(hash(BASE));
    }

    @Test
    @DisplayName("a changed value changes the hash")
    void valueChangeDetected() throws Exception {
        String changed = BASE.replace("\"hello\"", "\"goodbye\"");

        assertThat(hash(changed)).isNotEqualTo(hash(BASE));
    }

    @Test
    @DisplayName("an added key changes the hash")
    void addedKeyDetected() throws Exception {
        String changed = BASE.replace("\"server.port\": 9099", "\"server.port\": 9099, \"echo.extra\": 1");

        assertThat(hash(changed)).isNotEqualTo(hash(BASE));
    }

    @Test
    @DisplayName("a renamed property source changes the hash")
    void sourceNameChangeDetected() throws Exception {
        String changed = BASE.replace("echo-service.yml", "echo-service-renamed.yml");

        assertThat(hash(changed)).isNotEqualTo(hash(BASE));
    }

    @Test
    @DisplayName("source order is preserved because it decides precedence")
    void sourceOrderMatters() throws Exception {
        String swapped = """
                {
                  "name": "echo-service",
                  "profiles": ["local"],
                  "version": "abc123",
                  "state": null,
                  "propertySources": [
                    {
                      "name": "file:/config-repo/application.yml",
                      "source": {"management.endpoint.health.probes.enabled": true}
                    },
                    {
                      "name": "file:/config-repo/echo-service.yml",
                      "source": {"echo.greeting": "hello", "server.port": 9099}
                    }
                  ]
                }
                """;

        assertThat(hash(swapped)).isNotEqualTo(hash(BASE));
    }
}

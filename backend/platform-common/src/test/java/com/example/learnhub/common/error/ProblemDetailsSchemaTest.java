package com.example.learnhub.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bundled copy of the problem-details contract is what every error test validates against, so it
 * must not drift from the published contract in specs/.
 */
class ProblemDetailsSchemaTest {

    static final String RESOURCE = "schemas/problem-details.schema.json";

    private static final Path CONTRACT =
            Path.of("..", "..", "specs", "001-platform-foundation", "contracts", "problem-details.schema.json");

    static JsonNode loadSchema() throws Exception {
        try (InputStream in = ProblemDetailsSchemaTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(in).as("classpath resource " + RESOURCE).isNotNull();
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    @DisplayName("the bundled schema is identical to the published contract")
    void bundledSchemaMatchesContract() throws Exception {
        Assumptions.assumeTrue(Files.exists(CONTRACT), "contract file not present in this checkout");

        JsonNode published = new ObjectMapper().readTree(Files.readString(CONTRACT, StandardCharsets.UTF_8));

        assertThat(loadSchema()).isEqualTo(published);
    }

    @Test
    @DisplayName("the schema requires the fields every error must carry")
    void schemaRequiresCorrelationId() throws Exception {
        JsonNode required = loadSchema().get("required");

        assertThat(required.toString()).contains("correlationId").contains("status").contains("title");
    }
}

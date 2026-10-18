package com.example.learnhub.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Minimal JSON Schema checker covering the keywords this project's contracts actually use:
 * {@code required}, {@code additionalProperties: false}, {@code type}, {@code pattern},
 * {@code format} (uuid, date-time), {@code minimum} and {@code enum}.
 *
 * <p>Written by hand on purpose: constitution Principle X allows only JUnit 5, Testcontainers and
 * WireMock as test libraries, so pulling in a full JSON Schema validator would need an amendment.
 * The point of driving assertions from the schema file rather than a hand-listed field set is that
 * a change to the published contract shows up here instead of drifting silently.
 */
final class JsonSchemaCheck {

    private JsonSchemaCheck() {
    }

    static void assertValid(JsonNode schema, JsonNode instance) {
        List<String> violations = new ArrayList<>();
        JsonNode properties = schema.path("properties");

        for (JsonNode required : schema.path("required")) {
            if (!instance.has(required.asText())) {
                violations.add("missing required field: " + required.asText());
            }
        }

        if (schema.path("additionalProperties").asBoolean(true) == false) {
            instance.fieldNames().forEachRemaining(name -> {
                if (!properties.has(name)) {
                    violations.add("unexpected field not in schema: " + name);
                }
            });
        }

        instance.fieldNames().forEachRemaining(name -> {
            JsonNode rules = properties.path(name);
            JsonNode value = instance.get(name);
            if (rules.isMissingNode() || value.isNull()) {
                return;
            }
            violations.addAll(check(name, rules, value));
        });

        assertThat(violations).as("schema violations").isEmpty();
    }

    private static List<String> check(String name, JsonNode rules, JsonNode value) {
        List<String> violations = new ArrayList<>();

        String type = rules.path("type").asText("");
        boolean typeOk = switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "boolean" -> value.isBoolean();
            case "" -> true;
            default -> true;
        };
        if (!typeOk) {
            violations.add(name + ": expected type " + type + " but was " + value.getNodeType());
            return violations;
        }

        if (rules.hasNonNull("pattern") && value.isTextual()
                && !Pattern.compile(rules.get("pattern").asText()).matcher(value.asText()).find()) {
            violations.add(name + ": '" + value.asText() + "' does not match " + rules.get("pattern").asText());
        }

        String format = rules.path("format").asText("");
        if (value.isTextual()) {
            if ("uuid".equals(format)) {
                try {
                    UUID.fromString(value.asText());
                } catch (IllegalArgumentException e) {
                    violations.add(name + ": '" + value.asText() + "' is not a UUID");
                }
            } else if ("date-time".equals(format)) {
                try {
                    Instant.parse(value.asText());
                } catch (DateTimeParseException e) {
                    violations.add(name + ": '" + value.asText() + "' is not an ISO-8601 instant");
                }
            }
        }

        if (rules.hasNonNull("minimum") && value.isNumber()
                && value.decimalValue().compareTo(rules.get("minimum").decimalValue()) < 0) {
            violations.add(name + ": " + value.asText() + " is below minimum " + rules.get("minimum").asText());
        }

        if (rules.has("enum")) {
            boolean allowed = false;
            for (JsonNode option : rules.get("enum")) {
                if (option.equals(value)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                violations.add(name + ": " + value.asText() + " is not one of " + rules.get("enum"));
            }
        }

        return violations;
    }
}

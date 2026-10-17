package com.example.learnhub.common.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** FR-020: which client-supplied correlation IDs are reused, and which are replaced. */
class CorrelationIdsTest {

    @Test
    @DisplayName("header and MDC key names are the agreed ones")
    void constants() {
        assertThat(CorrelationIds.HEADER).isEqualTo("X-Correlation-Id");
        assertThat(CorrelationIds.MDC_KEY).isEqualTo("correlationId");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abcd1234",
            "6f1c2b7e-9d3a-4c55-8a10-2f4e7d9b1a33",
            "a.b-c_d1",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    })
    @DisplayName("well-formed IDs of 8 to 64 allowed characters are valid")
    void acceptsWellFormed(String id) {
        assertThat(CorrelationIds.isValid(id)).as(id).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abc1234",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "bad id!!",
            "has space",
            "semi;colon",
            "slash/slash",
            "cafécafe",
            ""
    })
    @DisplayName("too short, too long, or disallowed characters are invalid")
    void rejectsMalformed(String id) {
        assertThat(CorrelationIds.isValid(id)).as(id).isFalse();
    }

    @Test
    @DisplayName("null is invalid rather than throwing")
    void rejectsNull() {
        assertThat(CorrelationIds.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("a valid client value is reused unchanged")
    void resolveReusesValidValue() {
        assertThat(CorrelationIds.resolve("client-supplied-1")).isEqualTo("client-supplied-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "bad id!!", "has space"})
    @DisplayName("a malformed client value is replaced by a generated one that is itself valid")
    void resolveReplacesMalformed(String malformed) {
        String resolved = CorrelationIds.resolve(malformed);

        assertThat(resolved).isNotEqualTo(malformed);
        assertThat(CorrelationIds.isValid(resolved)).as("generated id must satisfy the same rule").isTrue();
    }

    @Test
    @DisplayName("a missing client value produces a fresh valid id each time")
    void resolveGeneratesForNull() {
        String first = CorrelationIds.resolve(null);
        String second = CorrelationIds.resolve(null);

        assertThat(CorrelationIds.isValid(first)).isTrue();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("generated ids are valid UUID strings")
    void generateProducesValidIds() {
        String generated = CorrelationIds.generate();

        assertThat(CorrelationIds.isValid(generated)).isTrue();
        assertThat(java.util.UUID.fromString(generated)).isNotNull();
    }
}

package com.example.learnhub.analytics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** FR-010: services trust gateway headers, so analytics-service checks the role itself as well. */
class CallerContextTest {

    private final String userId = UUID.randomUUID().toString();

    @Test
    void instructorIsAccepted() {
        CallerContext caller = CallerContext.require(userId, "INSTRUCTOR");

        assertThat(caller.userId()).isEqualTo(UUID.fromString(userId));
        assertThat(caller.isAdministrator()).isFalse();
    }

    @Test
    void administratorIsAccepted() {
        assertThat(CallerContext.require(userId, "ADMINISTRATOR").isAdministrator()).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"STUDENT", "ADMIN", "instructor", "", "ROOT"})
    void anyOtherRoleIsRefusedWith403(String role) {
        assertThatThrownBy(() -> CallerContext.require(userId, role))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "not-a-uuid", "123"})
    void aMissingOrMalformedUserIdIsRefusedWith403(String badId) {
        assertThatThrownBy(() -> CallerContext.require(badId, "INSTRUCTOR"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}

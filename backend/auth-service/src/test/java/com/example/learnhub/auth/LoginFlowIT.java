package com.example.learnhub.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.auth.infrastructure.LoginAttemptWindow;
import com.example.learnhub.auth.support.AbstractAuthServiceIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * FR-005 to FR-008: login issues a token pair on success; wrong password and unknown email are
 * indistinguishable; repeated failures are throttled (research.md R3: 5 failures per 60s window).
 */
class LoginFlowIT extends AbstractAuthServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoginAttemptWindow loginAttemptWindow;

    @BeforeEach
    void resetThrottling() {
        // Every MockMvc request shares one remote address, so a prior test's failures (or a prior test
        // class's, since the Spring context — and this singleton bean — is reused, support/AbstractAuthServiceIT)
        // must not bleed into this one.
        loginAttemptWindow.clearAll();
    }

    private static String body(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    private ResultActions register(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(email, password)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(email, password)));
    }

    @Test
    void correctCredentialsReturnATokenPair() throws Exception {
        register("lin@example.com", "correct-horse-1");

        login("lin@example.com", "correct-horse-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.expiresInSeconds").value(900));
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheIdenticalResponse() throws Exception {
        register("known@example.com", "correct-horse-1");

        String wrongPasswordBody = login("known@example.com", "totally-wrong-1")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownEmailBody = login("nobody@example.com", "whatever-does-not-matter-1")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(normalize(wrongPasswordBody)).isEqualTo(normalize(unknownEmailBody));
    }

    /** Strips the two fields that legitimately differ per request: instance (same here) and correlationId. */
    private static String normalize(String problemDetailsJson) {
        return problemDetailsJson
                .replaceAll("\"instance\":\"[^\"]*\"", "")
                .replaceAll("\"correlationId\":\"[^\"]*\"", "");
    }

    @Test
    void sixthRapidFailureAgainstTheSameAccountIsThrottled() throws Exception {
        register("throttle-me@example.com", "correct-horse-1");

        for (int attempt = 1; attempt <= 5; attempt++) {
            login("throttle-me@example.com", "wrong-" + attempt + "-1").andExpect(status().isUnauthorized());
        }

        login("throttle-me@example.com", "wrong-6-1").andExpect(status().isTooManyRequests());
    }
}

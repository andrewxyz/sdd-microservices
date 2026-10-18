package com.example.learnhub.auth;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.auth.support.AbstractAuthServiceIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * FR-001 to FR-004: registration creates a student account, rejects a duplicate (case-insensitive)
 * email, and rejects a password that does not meet the strength rule.
 */
class RegisterAccountIT extends AbstractAuthServiceIT {

    @Autowired
    private MockMvc mockMvc;

    private static String registerBody(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    @Test
    void registeringCreatesAStudentAccount() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("ada@example.com", "correct-horse-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void duplicateEmailIsRefusedEvenWhenOnlyCaseDiffers() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("grace@example.com", "correct-horse-1")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("GRACE@example.com", "another-password-1")))
                .andExpect(status().isConflict());
    }

    @Test
    void passwordUnderTenCharactersIsRefusedNamingTheRule() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("short@example.com", "ab1")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail", containsStringIgnoringCase("10 characters")));
    }

    @Test
    void passwordWithNoDigitIsRefusedNamingTheRule() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("nodigit@example.com", "no-digits-here")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail", containsStringIgnoringCase("digit")));
    }
}

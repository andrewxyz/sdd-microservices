package com.example.learnhub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.auth.support.AbstractAuthServiceIT;
import com.example.learnhub.common.identity.IdentityHeaders;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * FR-013, FR-014, FR-021: an administrator can change another account's role; the change applies on
 * the account's next login, not to a token already issued; the platform is never left with zero
 * administrators.
 */
class RoleChangeIT extends AbstractAuthServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void roleChangeTakesEffectOnNextLoginNotOnAnAlreadyIssuedToken() throws Exception {
        String email = "promote-me@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-1\"}"));

        String firstLoginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-1\"}"))
                .andReturn().getResponse().getContentAsString();
        String accessTokenBeforeChange = JsonPath.read(firstLoginBody, "$.accessToken");

        String meBody = mockMvc.perform(get("/api/v1/auth/me")
                        .header(IdentityHeaders.USER_ID, subjectOf(accessTokenBeforeChange)))
                .andReturn().getResponse().getContentAsString();
        String studentId = JsonPath.read(meBody, "$.id");

        mockMvc.perform(patch("/api/v1/auth/accounts/" + studentId + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"INSTRUCTOR\"}"))
                .andExpect(status().isOk());

        // The old token still claims the old role: nothing re-validates it against the account.
        assertThat(roleClaimOf(accessTokenBeforeChange)).isEqualTo("STUDENT");

        // A fresh login reflects the change.
        String secondLoginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-1\"}"))
                .andReturn().getResponse().getContentAsString();
        String accessTokenAfterChange = JsonPath.read(secondLoginBody, "$.accessToken");
        assertThat(roleClaimOf(accessTokenAfterChange)).isEqualTo("INSTRUCTOR");

        mockMvc.perform(get("/api/v1/auth/me").header(IdentityHeaders.USER_ID, studentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("INSTRUCTOR"));
    }

    @Test
    void demotingTheOnlyAdministratorIsRefused() throws Exception {
        // BootstrapAdministrator (support/AbstractAuthServiceIT's shared context) already created the
        // platform's one administrator; find it through the registered-student path is not possible, so
        // read it back via the repository would require a bean — instead, rely on the account created
        // by ADMIN_EMAIL in src/test/resources/application.yml and look it up through /me using a
        // known trick: register a throwaway student, promote it to a second administrator, demote the
        // original back to STUDENT (must succeed, two administrators exist), then demoting the last
        // remaining administrator must fail.
        String adminEmail = "admin@test.learnhub.example";
        String throwawayEmail = "second-admin@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + throwawayEmail + "\",\"password\":\"correct-horse-1\"}"));

        String throwawayLoginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + throwawayEmail + "\",\"password\":\"correct-horse-1\"}"))
                .andReturn().getResponse().getContentAsString();
        String throwawayAccessToken = JsonPath.read(throwawayLoginBody, "$.accessToken");
        String throwawayId = subjectOf(throwawayAccessToken);

        // Only one administrator exists so far: demoting it must be refused.
        String adminLoginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + adminEmail + "\",\"password\":\"admin-test-password-1\"}"))
                .andReturn().getResponse().getContentAsString();
        String adminAccessToken = JsonPath.read(adminLoginBody, "$.accessToken");
        String adminId = subjectOf(adminAccessToken);

        mockMvc.perform(patch("/api/v1/auth/accounts/" + adminId + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"STUDENT\"}"))
                .andExpect(status().isConflict());

        // Promoting the throwaway account to a second administrator must succeed (not the last one).
        mockMvc.perform(patch("/api/v1/auth/accounts/" + throwawayId + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMINISTRATOR\"}"))
                .andExpect(status().isOk());

        // Now that two administrators exist, demoting one of them succeeds.
        mockMvc.perform(patch("/api/v1/auth/accounts/" + adminId + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"STUDENT\"}"))
                .andExpect(status().isOk());

        // Restore state for any other test sharing this context.
        mockMvc.perform(patch("/api/v1/auth/accounts/" + adminId + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMINISTRATOR\"}"));
        mockMvc.perform(patch("/api/v1/auth/accounts/" + throwawayId + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"STUDENT\"}"));
    }

    /** Decodes the {@code sub} claim from a JWT's unsigned-for-reading-purposes payload segment. */
    private static String subjectOf(String jwt) {
        return (String) claims(jwt).get("sub");
    }

    private static String roleClaimOf(String jwt) {
        return (String) claims(jwt).get("role");
    }

    private static java.util.Map<?, ?> claims(String jwt) {
        String payload = jwt.split("\\.")[1];
        byte[] decoded = Base64.getUrlDecoder().decode(payload);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(new String(decoded, StandardCharsets.UTF_8), java.util.Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

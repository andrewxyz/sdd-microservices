package com.example.learnhub.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.auth.support.AbstractAuthServiceIT;
import com.example.learnhub.common.identity.IdentityHeaders;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * FR-010, FR-011: logout revokes the session's refresh token; a revoked token cannot be reused. A
 * second, independent session for the same account is unaffected (spec.md Clarifications 2026-10-18).
 */
class LogoutIT extends AbstractAuthServiceIT {

    @Autowired
    private MockMvc mockMvc;

    private String[] registerAndLogin(String email) throws Exception {
        String registerBody = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-1\"}"))
                .andReturn().getResponse().getContentAsString();
        String accountId = JsonPath.read(registerBody, "$.id");

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-1\"}"))
                .andReturn().getResponse().getContentAsString();
        String refreshToken = JsonPath.read(loginBody, "$.refreshToken");

        return new String[] {accountId, refreshToken};
    }

    @Test
    void logoutRevokesTheRefreshTokenForThatSession() throws Exception {
        String[] session = registerAndLogin("logout-me@example.com");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(IdentityHeaders.USER_ID, session[0])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + session[1] + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + session[1] + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loggingOutOneSessionDoesNotAffectAnotherSessionOnTheSameAccount() throws Exception {
        String email = "two-sessions@example.com";
        String[] sessionOne = registerAndLogin(email);

        String loginTwoBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-1\"}"))
                .andReturn().getResponse().getContentAsString();
        String refreshTokenTwo = JsonPath.read(loginTwoBody, "$.refreshToken");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(IdentityHeaders.USER_ID, sessionOne[0])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + sessionOne[1] + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshTokenTwo + "\"}"))
                .andExpect(status().isOk());
    }
}

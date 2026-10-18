package com.example.learnhub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.learnhub.auth.support.AbstractAuthServiceIT;
import com.example.learnhub.common.identity.IdentityHeaders;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** FR-023: a logged-in person can see their own profile, with no password or token data in it. */
class ViewOwnAccountIT extends AbstractAuthServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void meReturnsTheOwnProfileWithNoSensitiveField() throws Exception {
        String email = "view-me@example.com";
        String registerBody = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-1\"}"))
                .andReturn().getResponse().getContentAsString();
        String accountId = JsonPath.read(registerBody, "$.id");

        String meBody = mockMvc.perform(get("/api/v1/auth/me").header(IdentityHeaders.USER_ID, accountId))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(meBody, "$.email")).isEqualTo(email);
        assertThat(JsonPath.<String>read(meBody, "$.role")).isEqualTo("STUDENT");
        assertThat(JsonPath.<String>read(meBody, "$.createdAt")).isNotNull();
        assertThat(meBody).doesNotContain("password").doesNotContain("Password")
                .doesNotContain("accessToken").doesNotContain("refreshToken");
    }
}

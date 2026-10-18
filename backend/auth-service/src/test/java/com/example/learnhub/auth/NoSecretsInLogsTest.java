package com.example.learnhub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.learnhub.auth.support.AbstractAuthServiceIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** FR-024: no log output anywhere may contain a password, an access token, or a refresh token. */
class NoSecretsInLogsTest extends AbstractAuthServiceIT {

    private static final String PASSWORD = "very-secret-password-1";

    @Autowired
    private MockMvc mockMvc;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        rootLogger().addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        rootLogger().detachAppender(appender);
    }

    private Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    }

    @Test
    void registerAndLoginNeverLogThePasswordOrEitherToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"log-safety@example.com\",\"password\":\"" + PASSWORD + "\"}"));

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"log-safety@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString();

        String accessToken = extractJsonField(loginResponse, "accessToken");
        String refreshToken = extractJsonField(loginResponse, "refreshToken");

        for (ILoggingEvent event : appender.list) {
            String formatted = event.getFormattedMessage();
            assertThat(formatted).doesNotContain(PASSWORD);
            assertThat(formatted).doesNotContain(accessToken);
            assertThat(formatted).doesNotContain(refreshToken);
        }
    }

    private static String extractJsonField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}

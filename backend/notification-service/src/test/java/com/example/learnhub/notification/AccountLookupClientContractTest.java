package com.example.learnhub.notification;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.learnhub.notification.infrastructure.AccountLookupClient;
import com.example.learnhub.notification.infrastructure.AccountLookupClientFallbackFactory;
import com.example.learnhub.notification.infrastructure.AccountSummaryResponse;
import com.example.learnhub.notification.infrastructure.LookupUnavailableException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * WireMock-stubbed contract test for notification-service's {@link AccountLookupClient} (research.md R5;
 * constitution Principle VI).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = AccountLookupClientContractTest.TestConfig.class)
class AccountLookupClientContractTest {

    @Configuration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class, RabbitAutoConfiguration.class})
    @EnableFeignClients(clients = AccountLookupClient.class)
    @Import(AccountLookupClientFallbackFactory.class)
    static class TestConfig {
    }

    private static final WireMockServer AUTH_SERVICE = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        AUTH_SERVICE.start();
    }

    @DynamicPropertySource
    static void feignUrl(DynamicPropertyRegistry registry) {
        registry.add("auth-service.url", AUTH_SERVICE::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        AUTH_SERVICE.stop();
    }

    @Autowired
    private AccountLookupClient client;

    @Test
    void mapsA200ToAnAccountSummaryWithEmail() {
        UUID accountId = UUID.randomUUID();
        AUTH_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/accounts/" + accountId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"%s\",\"email\":\"student@example.com\"}".formatted(accountId))));

        AccountSummaryResponse summary = client.summary(accountId);

        assertThat(summary.email()).isEqualTo("student@example.com");
    }

    @Test
    void mapsA5xxToAThrownException() {
        UUID accountId = UUID.randomUUID();
        AUTH_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/accounts/" + accountId))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.summary(accountId))
                .isInstanceOf(LookupUnavailableException.class);
    }

    @Test
    void mapsATimeoutToAThrownException() {
        UUID accountId = UUID.randomUUID();
        AUTH_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/accounts/" + accountId))
                .willReturn(aResponse().withFixedDelay(20_000).withStatus(200)));

        assertThatThrownBy(() -> client.summary(accountId))
                .isInstanceOf(LookupUnavailableException.class);
    }
}

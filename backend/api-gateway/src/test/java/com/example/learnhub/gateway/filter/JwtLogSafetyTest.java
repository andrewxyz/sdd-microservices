package com.example.learnhub.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.learnhub.gateway.security.JwtSigningKey;
import com.example.learnhub.gateway.security.JwtVerifier;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * specs/002-user-auth analyze finding G1: FR-024/SC-008 apply to the gateway too, not only
 * auth-service — the gateway reads the raw {@code Authorization} header on every protected request, so
 * a log line written during verification failure is just as capable of leaking a token as anything in
 * auth-service.
 */
class JwtLogSafetyTest {

    private static final String SECRET = "test-only-signing-secret-at-least-32-bytes-long";

    private final JwtVerifier verifier = new JwtVerifier(new JwtSigningKey(SECRET));
    private final JwtAuthenticationGlobalFilter filter = new JwtAuthenticationGlobalFilter(verifier, 100);

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

    private ServerWebExchange exchangeWithAuthorization(String headerValue) {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/x").header(HttpHeaders.AUTHORIZATION, headerValue).build());
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id("probe").uri("forward:/__probe").order(0).predicate(ex -> true)
                        .metadata(Map.of()).build());
        return exchange;
    }

    private void assertNoLogContains(String secretValue) {
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(secretValue);
        }
    }

    @Test
    void missingTokenCaseNeverLogsTheAuthorizationHeaderValue() {
        try {
            filter.filter(exchangeWithAuthorization(""), ex -> Mono.empty()).block();
        } catch (Exception ignored) {
            // Expected: the filter refuses the request. Only the log output matters here.
        }
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage()).doesNotContainIgnoringCase("bearer");
        }
    }

    @Test
    void tamperedTokenCaseNeverLogsTheRawToken() {
        String token = signedToken(UUID.randomUUID().toString(), Instant.now().plusSeconds(900));
        String tampered = token.substring(0, token.length() - 1) + "x";
        try {
            filter.filter(exchangeWithAuthorization("Bearer " + tampered), ex -> Mono.empty()).block();
        } catch (Exception ignored) {
            // Expected.
        }
        assertNoLogContains(tampered);
    }

    @Test
    void expiredTokenCaseNeverLogsTheRawToken() {
        String token = signedToken(UUID.randomUUID().toString(), Instant.now().minusSeconds(1));
        try {
            filter.filter(exchangeWithAuthorization("Bearer " + token), ex -> Mono.empty()).block();
        } catch (Exception ignored) {
            // Expected.
        }
        assertNoLogContains(token);
    }

    private static String signedToken(String subject, Instant expiry) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("role", "STUDENT")
                .issueTime(Date.from(Instant.now().minusSeconds(10)))
                .expirationTime(Date.from(expiry))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(SECRET.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return jwt.serialize();
    }
}

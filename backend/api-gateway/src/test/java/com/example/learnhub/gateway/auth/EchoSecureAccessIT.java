package com.example.learnhub.gateway.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.gateway.security.IdentityHeaders;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * FR-022: the echo service's existing protected path — previously an unconditional 401 (research.md R5
 * / platform-foundation) — now actually succeeds with a valid token, and the upstream receives identity
 * headers but never the raw token (FR-020). Reuses the WireMock-stubbed-upstream pattern from
 * {@code GatewayBehaviourIT}; a separate file keeps this feature's config isolated from that one's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("echosecureaccessit")
class EchoSecureAccessIT {

    private static final String SECRET = "test-only-signing-secret-at-least-32-bytes-long";
    private static final Path CONFIG = Path.of("target", "echo-secure-access-it", "config.yml");

    private static WireMockServer upstream;

    @BeforeAll
    static void startUpstreamAndWriteConfig() throws Exception {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
        upstream.stubFor(get(urlPathEqualTo("/api/v1/echo/secure/ping"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));
        upstream.stubFor(get(urlPathEqualTo("/api/v1/admin-only/thing"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        Files.createDirectories(CONFIG.getParent());
        Files.writeString(CONFIG, """
                spring:
                  cloud:
                    gateway:
                      server:
                        webflux:
                          routes:
                            - id: echo-secure
                              uri: http://localhost:%d
                              order: 0
                              predicates: ["Path=/api/v1/echo/secure/**"]
                            # Deliberately written exactly like the real api-gateway.yml's auth-admin
                            # route: a real YAML list under route metadata, bound by Spring's actual
                            # relaxed binder (not a hand-built java.util.List in a unit test), which is
                            # what caught this feature's one real production bug — YAML list metadata
                            # binds to a Map keyed "0", "1", ..., not a List (research.md / tasks.md T052).
                            - id: admin-only
                              uri: http://localhost:%d
                              order: 1
                              predicates: ["Path=/api/v1/admin-only/**"]
                              metadata:
                                roles: [ADMINISTRATOR]
                platform:
                  gateway:
                    rate-limit:
                      capacity: 200
                      refill-per-second: 100
                      idle-eviction: PT10M
                """.formatted(upstream.port(), upstream.port()));
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @Autowired
    private WebTestClient client;

    private static String validToken(String role) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .claim("role", role)
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(SECRET.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return jwt.serialize();
    }

    @Test
    @DisplayName("no token: refused with 401")
    void noTokenIsRefused() {
        client.get().uri("/api/v1/echo/secure/ping").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("valid token: succeeds, and the upstream receives identity headers but not the token")
    void validTokenSucceedsAndForwardsIdentityOnly() {
        client.get().uri("/api/v1/echo/secure/ping")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("STUDENT"))
                .exchange()
                .expectStatus().isOk();

        upstream.verify(getRequestedFor(urlPathEqualTo("/api/v1/echo/secure/ping"))
                .withHeader(IdentityHeaders.USER_ROLE, com.github.tomakehurst.wiremock.client.WireMock.equalTo("STUDENT")));
        upstream.verify(getRequestedFor(urlPathEqualTo("/api/v1/echo/secure/ping"))
                .withoutHeader(HttpHeaders.AUTHORIZATION));
    }

    @Test
    @DisplayName("role-restricted route: valid token with the wrong role is refused with 403")
    void insufficientRoleIsForbiddenThroughTheRealGateway() {
        client.get().uri("/api/v1/admin-only/thing")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("STUDENT"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("role-restricted route: valid token with the listed role succeeds")
    void sufficientRolePassesThroughTheRealGateway() {
        client.get().uri("/api/v1/admin-only/thing")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("ADMINISTRATOR"))
                .exchange()
                .expectStatus().isOk();
    }
}

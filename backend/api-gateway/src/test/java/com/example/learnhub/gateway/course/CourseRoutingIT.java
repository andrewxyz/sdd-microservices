package com.example.learnhub.gateway.course;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

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
 * specs/003-course-catalog research.md R2: proves the route design against a real, YAML-loaded gateway
 * context rather than only a hand-built unit test — the exact gap that let 002's role-metadata binding
 * bug through. In particular: the public single-course-detail route (a single-segment `*`) must never
 * swallow `/api/v1/my/courses`, and `courses-manage`/`categories-admin` must actually require a token
 * and, for categories, the ADMINISTRATOR role.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("courseroutingit")
class CourseRoutingIT {

    private static final String SECRET = "test-only-signing-secret-at-least-32-bytes-long";
    private static final Path CONFIG = Path.of("target", "course-routing-it", "config.yml");

    private static WireMockServer upstream;

    @BeforeAll
    static void startUpstreamAndWriteConfig() throws Exception {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
        upstream.stubFor(get(urlPathEqualTo("/api/v1/courses"))
                .willReturn(aResponse().withStatus(200).withBody("{\"items\":[]}")));
        upstream.stubFor(get(urlPathEqualTo("/api/v1/courses/some-course-id"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));
        upstream.stubFor(get(urlPathEqualTo("/api/v1/my/courses"))
                .willReturn(aResponse().withStatus(200).withBody("[]")));
        upstream.stubFor(post(urlPathEqualTo("/api/v1/courses"))
                .willReturn(aResponse().withStatus(201).withBody("{\"ok\":true}")));
        upstream.stubFor(post(urlPathEqualTo("/api/v1/categories"))
                .willReturn(aResponse().withStatus(201).withBody("{\"ok\":true}")));

        Files.createDirectories(CONFIG.getParent());
        Files.writeString(CONFIG, """
                spring:
                  cloud:
                    gateway:
                      server:
                        webflux:
                          routes:
                            - id: courses-public-list
                              uri: http://localhost:%d
                              order: 0
                              predicates: ["Path=/api/v1/courses", "Method=GET"]
                              metadata:
                                public: true
                            - id: courses-public-detail
                              uri: http://localhost:%d
                              order: 1
                              predicates: ["Path=/api/v1/courses/*", "Method=GET"]
                              metadata:
                                public: true
                            - id: categories-public-list
                              uri: http://localhost:%d
                              order: 2
                              predicates: ["Path=/api/v1/categories", "Method=GET"]
                              metadata:
                                public: true
                            - id: categories-admin
                              uri: http://localhost:%d
                              order: 3
                              predicates: ["Path=/api/v1/categories/**"]
                              metadata:
                                roles: [ADMINISTRATOR]
                            - id: my-courses
                              uri: http://localhost:%d
                              order: 4
                              predicates: ["Path=/api/v1/my/courses"]
                            - id: courses-manage
                              uri: http://localhost:%d
                              order: 5
                              predicates: ["Path=/api/v1/courses/**"]
                platform:
                  gateway:
                    rate-limit:
                      capacity: 200
                      refill-per-second: 100
                      idle-eviction: PT10M
                """.formatted(upstream.port(), upstream.port(), upstream.port(), upstream.port(),
                        upstream.port(), upstream.port()));
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @Autowired
    private WebTestClient client;

    private static String token(String role) {
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
    @DisplayName("the public catalog list and single-course detail need no token")
    void publicRoutesNeedNoToken() {
        client.get().uri("/api/v1/courses").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/courses/some-course-id").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("my-courses is never swallowed by the public single-course-detail route")
    void myCoursesIsNeverPublic() {
        client.get().uri("/api/v1/my/courses").exchange().expectStatus().isUnauthorized();

        client.get().uri("/api/v1/my/courses")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("INSTRUCTOR"))
                .exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("creating a course requires a token")
    void createCourseRequiresToken() {
        client.post().uri("/api/v1/courses").exchange().expectStatus().isUnauthorized();

        client.post().uri("/api/v1/courses")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("INSTRUCTOR"))
                .exchange().expectStatus().isCreated();
    }

    @Test
    @DisplayName("creating a category requires the ADMINISTRATOR role")
    void createCategoryRequiresAdministrator() {
        client.post().uri("/api/v1/categories")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("INSTRUCTOR"))
                .exchange().expectStatus().isForbidden();

        client.post().uri("/api/v1/categories")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("ADMINISTRATOR"))
                .exchange().expectStatus().isCreated();
    }
}

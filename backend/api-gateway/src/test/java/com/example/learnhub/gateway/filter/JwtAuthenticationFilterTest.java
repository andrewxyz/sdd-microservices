package com.example.learnhub.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.learnhub.gateway.GatewayConfiguration;
import com.example.learnhub.gateway.ratelimit.RateLimitGlobalFilter;
import com.example.learnhub.gateway.security.IdentityHeaders;
import com.example.learnhub.gateway.security.JwtSigningKey;
import com.example.learnhub.gateway.security.JwtVerifier;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Constitution Principle IV (protected by default) and specs/002-user-auth FR-016 to FR-020: a route
 * is reachable without a token only if it says so explicitly; every other route needs a valid,
 * unexpired, untampered token, and a route whose metadata lists {@code roles} additionally needs the
 * token's role to be one of them (FR-019).
 *
 * <p>Routes are built with Spring Cloud Gateway's own {@link Route#async()} builder, and tokens are
 * real signed JWTs built with the same library the filter verifies with — not a mock — keeping this
 * module within the constitution's approved test tooling (JUnit 5, Testcontainers, WireMock only).
 */
class JwtAuthenticationFilterTest {

    private static final String TEST_SECRET = "test-only-signing-secret-at-least-32-bytes-long";

    private final JwtVerifier verifier = new JwtVerifier(new JwtSigningKey(TEST_SECRET));
    private final JwtAuthenticationGlobalFilter filter = new JwtAuthenticationGlobalFilter(verifier, 100);

    private static String token(String subject, String role, Instant expiry) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("role", role)
                .issueTime(Date.from(Instant.now().minusSeconds(10)))
                .expirationTime(Date.from(expiry))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(TEST_SECRET.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return jwt.serialize();
    }

    private static String validToken(String role) {
        return token(UUID.randomUUID().toString(), role, Instant.now().plus(15, ChronoUnit.MINUTES));
    }

    private ServerWebExchange exchangeForRoute(Route route, String authorizationHeader) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/x");
        if (authorizationHeader != null) {
            builder = builder.header(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }
        ServerWebExchange exchange = MockServerWebExchange.from(builder.build());
        if (route != null) {
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        }
        return exchange;
    }

    private Route routeWithMetadata(Map<String, Object> metadata) {
        return Route.async()
                .id("probe")
                .uri("forward:/__probe")
                .order(0)
                .predicate(ex -> true)
                .metadata(metadata)
                .build();
    }

    // -------------------------------------------------------------- public routes (unchanged behaviour)

    @ParameterizedTest
    @ValueSource(booleans = {true})
    @DisplayName("metadata.public as a real Boolean true passes the request through with no token")
    void booleanTruePassesWithNoToken(boolean ignored) {
        AtomicBoolean passed = new AtomicBoolean(false);
        ServerWebExchange exchange = exchangeForRoute(routeWithMetadata(Map.of("public", Boolean.TRUE)), null);

        filter.filter(exchange, ex -> {
            passed.set(true);
            return Mono.empty();
        }).block();

        assertThat(passed).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True"})
    @DisplayName("metadata.public as the quoted string \"true\" (any case) also passes the request through")
    void stringTruePasses(String value) {
        AtomicBoolean passed = new AtomicBoolean(false);
        ServerWebExchange exchange = exchangeForRoute(routeWithMetadata(Map.of("public", value)), null);

        filter.filter(exchange, ex -> {
            passed.set(true);
            return Mono.empty();
        }).block();

        assertThat(passed).isTrue();
    }

    @Test
    @DisplayName("no route attribute at all means protected")
    void noRouteAttributeMeansProtected() {
        ServerWebExchange exchange = exchangeForRoute(null, null);

        assertThatThrownBy(() -> filter.filter(exchange, ex -> Mono.empty()).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------- protected routes: token checks

    @Test
    @DisplayName("missing Authorization header is refused with 401")
    void missingTokenIsUnauthorized() {
        ServerWebExchange exchange = exchangeForRoute(routeWithMetadata(Map.of()), null);

        assertThatThrownBy(() -> filter.filter(exchange, ex -> Mono.empty()).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a malformed token is refused with 401")
    void malformedTokenIsUnauthorized() {
        ServerWebExchange exchange = exchangeForRoute(routeWithMetadata(Map.of()), "Bearer not-a-real-jwt");

        assertThatThrownBy(() -> filter.filter(exchange, ex -> Mono.empty()).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a token signed with a different secret (tampered) is refused with 401")
    void tamperedTokenIsUnauthorized() {
        String foreignToken = token(UUID.randomUUID().toString(), "STUDENT", Instant.now().plusSeconds(900));
        // Re-sign is impossible without the real secret, so simulate tampering by flipping a character
        // in the payload segment, which breaks the signature the same way an attacker's edit would.
        // Deliberately not the token's very last character: that falls in the base64url signature's
        // final group, where some bits are unused padding a decoder may ignore, making a flip there
        // flaky rather than reliably invalid.
        int payloadEnd = foreignToken.lastIndexOf('.');
        char flipped = foreignToken.charAt(payloadEnd - 1) == 'A' ? 'B' : 'A';
        String tampered = foreignToken.substring(0, payloadEnd - 1) + flipped
                + foreignToken.substring(payloadEnd);
        ServerWebExchange exchange = exchangeForRoute(routeWithMetadata(Map.of()), "Bearer " + tampered);

        assertThatThrownBy(() -> filter.filter(exchange, ex -> Mono.empty()).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an expired token is refused with 401")
    void expiredTokenIsUnauthorized() {
        String expired = token(UUID.randomUUID().toString(), "STUDENT", Instant.now().minusSeconds(1));
        ServerWebExchange exchange = exchangeForRoute(routeWithMetadata(Map.of()), "Bearer " + expired);

        assertThatThrownBy(() -> filter.filter(exchange, ex -> Mono.empty()).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a valid token reaches the chain with identity headers set and Authorization removed")
    void validTokenSetsIdentityHeadersAndRemovesAuthorization() {
        String accountId = UUID.randomUUID().toString();
        ServerWebExchange exchange = exchangeForRoute(routeWithMetadata(Map.of()), "Bearer " + validToken("STUDENT"));

        var downstreamRequest = new java.util.concurrent.atomic.AtomicReference<HttpHeaders>();
        filter.filter(exchange, ex -> {
            downstreamRequest.set(ex.getRequest().getHeaders());
            return Mono.empty();
        }).block();

        HttpHeaders seenByDownstream = downstreamRequest.get();
        assertThat(seenByDownstream).isNotNull();
        assertThat(seenByDownstream.getFirst(IdentityHeaders.USER_ROLE)).isEqualTo("STUDENT");
        assertThat(seenByDownstream.getFirst(HttpHeaders.AUTHORIZATION))
                .as("the destination service must never receive the raw token (FR-020)").isNull();
    }

    @Test
    @DisplayName("a client-supplied X-User-Id/X-User-Role is stripped even on a public route")
    void identityHeadersAreStrippedOnPublicRoutes() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/x")
                .header(IdentityHeaders.USER_ID, "forged-id")
                .header(IdentityHeaders.USER_ROLE, "ADMINISTRATOR")
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                routeWithMetadata(Map.of("public", true)));

        var downstreamRequest = new java.util.concurrent.atomic.AtomicReference<HttpHeaders>();
        filter.filter(exchange, ex -> {
            downstreamRequest.set(ex.getRequest().getHeaders());
            return Mono.empty();
        }).block();

        assertThat(downstreamRequest.get().getFirst(IdentityHeaders.USER_ID)).isNull();
        assertThat(downstreamRequest.get().getFirst(IdentityHeaders.USER_ROLE)).isNull();
    }

    // --------------------------------------------------------------------------- role-restricted routes

    @Test
    @DisplayName("a role-restricted route refuses a valid token whose role is not listed, with 403")
    void insufficientRoleIsForbidden() {
        ServerWebExchange exchange = exchangeForRoute(
                routeWithMetadata(Map.of("roles", List.of("ADMINISTRATOR"))), "Bearer " + validToken("STUDENT"));

        assertThatThrownBy(() -> filter.filter(exchange, ex -> Mono.empty()).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a role-restricted route passes a valid token whose role is listed")
    void sufficientRolePasses() {
        AtomicBoolean passed = new AtomicBoolean(false);
        ServerWebExchange exchange = exchangeForRoute(
                routeWithMetadata(Map.of("roles", List.of("ADMINISTRATOR"))),
                "Bearer " + validToken("ADMINISTRATOR"));

        filter.filter(exchange, ex -> {
            passed.set(true);
            return Mono.empty();
        }).block();

        assertThat(passed).isTrue();
    }

    // ------------------------------------------------------------------------------------ filter order

    @Test
    @DisplayName("filter order is correlation ID, then JWT authentication, then the rate limiter")
    void filterOrderMatchesTheSpec() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(GatewayConfiguration.class,
                        com.example.learnhub.gateway.security.JwtSigningKey.class,
                        JwtVerifier.class)
                .withPropertyValues(
                        "platform.gateway.rate-limit.capacity=10",
                        "platform.gateway.rate-limit.refill-per-second=1",
                        "JWT_SIGNING_SECRET=" + TEST_SECRET)
                .run(context -> {
                    List<GlobalFilter> filters = context.getBeanProvider(GlobalFilter.class).orderedStream().toList();

                    int correlationIndex = indexOfType(filters, CorrelationIdGlobalFilter.class);
                    int jwtIndex = indexOfType(filters, JwtAuthenticationGlobalFilter.class);
                    int rateLimitIndex = indexOfType(filters, RateLimitGlobalFilter.class);

                    assertThat(correlationIndex).as("correlation filter must be registered").isNotEqualTo(-1);
                    assertThat(jwtIndex).as("JWT authentication filter must be registered").isNotEqualTo(-1);
                    assertThat(rateLimitIndex).as("rate limit filter must be registered").isNotEqualTo(-1);

                    assertThat(correlationIndex).as("correlation ID runs before JWT authentication")
                            .isLessThan(jwtIndex);
                    assertThat(jwtIndex).as("JWT authentication runs before the rate limiter")
                            .isLessThan(rateLimitIndex);
                });
    }

    private static int indexOfType(List<GlobalFilter> filters, Class<?> type) {
        for (int i = 0; i < filters.size(); i++) {
            if (type.isInstance(filters.get(i))) {
                return i;
            }
        }
        return -1;
    }
}

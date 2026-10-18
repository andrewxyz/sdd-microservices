package com.example.learnhub.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.learnhub.gateway.error.GatewayErrorHandler;
import com.example.learnhub.gateway.ratelimit.ClientAddressResolver;
import com.example.learnhub.gateway.ratelimit.RateLimitGlobalFilter;
import com.example.learnhub.gateway.ratelimit.RateLimiterProperties;
import com.example.learnhub.gateway.ratelimit.RemoteAddressClientResolver;
import com.example.learnhub.gateway.ratelimit.TokenBucketRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Constitution Principle IV (protected by default) and FR-023: a route is reachable without a login
 * only if it says so explicitly, and metadata may spell that out as either a Boolean or a String.
 *
 * <p>Routes are built with Spring Cloud Gateway's own {@link Route#async()} builder rather than a
 * mock, keeping this module within the constitution's approved test tooling (JUnit 5, Testcontainers,
 * WireMock only).
 */
class RouteAccessFilterTest {

    private final RouteAccessGlobalFilter filter = new RouteAccessGlobalFilter(100);

    private ServerWebExchange exchangeForRoute(Route route) {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x").build());
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
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

    @ParameterizedTest
    @ValueSource(booleans = {true})
    @DisplayName("metadata.public as a real Boolean true passes the request through")
    void booleanTruePasses(boolean ignored) {
        var passed = new java.util.concurrent.atomic.AtomicBoolean(false);
        ServerWebExchange exchange = exchangeForRoute(routeWithMetadata(Map.of("public", Boolean.TRUE)));

        filter.filter(exchange, (ex) -> {
            passed.set(true);
            return Mono.empty();
        }).block();

        assertThat(passed).as("chain.filter must be called for a public route").isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True"})
    @DisplayName("metadata.public as the quoted string \"true\" (any case) also passes the request through")
    void stringTruePasses(String value) {
        var passed = new java.util.concurrent.atomic.AtomicBoolean(false);
        ServerWebExchange exchange = exchangeForRoute(routeWithMetadata(Map.of("public", value)));

        filter.filter(exchange, (ex) -> {
            passed.set(true);
            return Mono.empty();
        }).block();

        assertThat(passed).isTrue();
    }

    @Test
    @DisplayName("no route metadata at all means protected")
    void noRouteAttributeMeansProtected() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x").build());

        assertThatThrownBy(() -> filter.filter(exchange, ex -> Mono.empty()).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("no public key in metadata means protected")
    void absentMetadataKeyMeansProtected() {
        ServerWebExchange exchange = exchangeForRoute(routeWithMetadata(Map.of()));

        assertThatThrownBy(() -> filter.filter(exchange, ex -> Mono.empty()).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", "yes", "1", "nope", ""})
    @DisplayName("public: false, or anything other than true, means protected")
    void anyOtherValueMeansProtected(String value) {
        ServerWebExchange exchange = exchangeForRoute(routeWithMetadata(Map.of("public", value)));

        assertThatThrownBy(() -> filter.filter(exchange, ex -> Mono.empty()).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a Boolean false in metadata means protected, not merely absent-equals-protected")
    void booleanFalseMeansProtected() {
        ServerWebExchange exchange = exchangeForRoute(routeWithMetadata(Map.of("public", Boolean.FALSE)));

        assertThatThrownBy(() -> filter.filter(exchange, ex -> Mono.empty()).block())
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("filter order is correlation ID, then route access, then the rate limiter")
    void filterOrderMatchesTheSpec() {
        // Real beans as GatewayConfiguration would construct them, loaded through a minimal reactive
        // context so the actual order values wired in production are what gets compared, not a
        // hand-copied constant that could drift from the real configuration.
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(com.example.learnhub.gateway.GatewayConfiguration.class)
                .withPropertyValues(
                        "platform.gateway.rate-limit.capacity=10",
                        "platform.gateway.rate-limit.refill-per-second=1")
                .run(context -> {
                    List<GlobalFilter> filters = context.getBeanProvider(GlobalFilter.class).orderedStream().toList();

                    int correlationIndex = indexOfType(filters, CorrelationIdGlobalFilter.class);
                    int routeAccessIndex = indexOfType(filters, RouteAccessGlobalFilter.class);
                    int rateLimitIndex = indexOfType(filters, RateLimitGlobalFilter.class);

                    assertThat(correlationIndex).as("correlation filter must be registered").isNotEqualTo(-1);
                    assertThat(routeAccessIndex).as("route access filter must be registered").isNotEqualTo(-1);
                    assertThat(rateLimitIndex).as("rate limit filter must be registered").isNotEqualTo(-1);

                    assertThat(correlationIndex).as("correlation ID runs before route access")
                            .isLessThan(routeAccessIndex);
                    assertThat(routeAccessIndex).as("route access runs before the rate limiter")
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

package com.example.learnhub.gateway.ratelimit;

import java.util.Map;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Applies the per-client rate limit (FR-024).
 *
 * <p>Ordered after route access, so an unauthenticated caller gets 401 rather than spending tokens
 * and getting 429 for a route it could never reach. CORS preflight never arrives here at all: the
 * gateway answers preflight before the filter chain runs (research R2), so a browser's preflight is
 * not charged against the limit.
 *
 * <p>specs/005-progress-certificates-notifications research.md R8: a route whose metadata carries
 * {@code rate-limit: strict} (currently only certificate verification — a public, guessable-code
 * endpoint) is checked against a second, stricter bucket instead of the platform's general one. This
 * is the gateway's first per-route rate limit; every other route keeps using the single general bucket
 * unchanged.
 */
public class RateLimitGlobalFilter implements GlobalFilter, Ordered {

    /** Set on the exchange so the error handler can add Retry-After to the 429 it renders. */
    public static final String RETRY_AFTER_ATTRIBUTE = "learnhub.retryAfterSeconds";
    /** Route metadata key selecting the stricter bucket (research.md R8). */
    public static final String RATE_LIMIT_METADATA_KEY = "rate-limit";
    public static final String STRICT_METADATA_VALUE = "strict";

    private final TokenBucketRateLimiter limiter;
    private final TokenBucketRateLimiter strictLimiter;
    private final ClientAddressResolver resolver;
    private final int order;

    public RateLimitGlobalFilter(TokenBucketRateLimiter limiter, TokenBucketRateLimiter strictLimiter,
            ClientAddressResolver resolver, int order) {
        this.limiter = limiter;
        this.strictLimiter = strictLimiter;
        this.resolver = resolver;
        this.order = order;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        TokenBucketRateLimiter applicable = isStrict(exchange) ? strictLimiter : limiter;
        String client = resolver.resolve(exchange);
        if (applicable.tryConsume(client)) {
            return chain.filter(exchange);
        }
        exchange.getAttributes().put(RETRY_AFTER_ATTRIBUTE, applicable.retryAfterSeconds(client));
        return Mono.error(new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded for this client address."));
    }

    private static boolean isStrict(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return false;
        }
        Map<String, Object> metadata = route.getMetadata();
        if (metadata == null) {
            return false;
        }
        Object value = metadata.get(RATE_LIMIT_METADATA_KEY);
        return STRICT_METADATA_VALUE.equals(String.valueOf(value));
    }
}

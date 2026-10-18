package com.example.learnhub.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
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
 */
public class RateLimitGlobalFilter implements GlobalFilter, Ordered {

    /** Set on the exchange so the error handler can add Retry-After to the 429 it renders. */
    public static final String RETRY_AFTER_ATTRIBUTE = "learnhub.retryAfterSeconds";

    private final TokenBucketRateLimiter limiter;
    private final ClientAddressResolver resolver;
    private final int order;

    public RateLimitGlobalFilter(TokenBucketRateLimiter limiter, ClientAddressResolver resolver, int order) {
        this.limiter = limiter;
        this.resolver = resolver;
        this.order = order;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String client = resolver.resolve(exchange);
        if (limiter.tryConsume(client)) {
            return chain.filter(exchange);
        }
        exchange.getAttributes().put(RETRY_AFTER_ATTRIBUTE, limiter.retryAfterSeconds(client));
        return Mono.error(new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded for this client address."));
    }
}

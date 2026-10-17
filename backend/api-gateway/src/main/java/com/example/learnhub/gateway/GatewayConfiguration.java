package com.example.learnhub.gateway;

import com.example.learnhub.common.correlation.CorrelationIds;
import com.example.learnhub.gateway.error.GatewayErrorHandler;
import com.example.learnhub.gateway.filter.CorrelationIdGlobalFilter;
import com.example.learnhub.gateway.filter.RouteAccessGlobalFilter;
import com.example.learnhub.gateway.ratelimit.ClientAddressResolver;
import com.example.learnhub.gateway.ratelimit.RateLimitGlobalFilter;
import com.example.learnhub.gateway.ratelimit.RateLimiterProperties;
import com.example.learnhub.gateway.ratelimit.RemoteAddressClientResolver;
import com.example.learnhub.gateway.ratelimit.TokenBucketRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.endpoint.event.RefreshEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;

/**
 * Wires the gateway's global filters, in the order the spec requires: correlation ID, then route
 * access, then the rate limiter.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimiterProperties.class)
public class GatewayConfiguration {

    /** Correlation first, so even a 401 or 429 carries an ID. */
    private static final int CORRELATION_ORDER = Ordered.HIGHEST_PRECEDENCE;
    /** Access next: an unauthorised caller should not spend rate-limit tokens. */
    private static final int ROUTE_ACCESS_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    /** Rate limit last of the three, applying only to requests that could actually proceed. */
    private static final int RATE_LIMIT_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    private final TokenBucketRateLimiter rateLimiter;

    public GatewayConfiguration(RateLimiterProperties properties) {
        this.rateLimiter = new TokenBucketRateLimiter(properties, Instant::now);
    }

    /**
     * The MDC accessor is not registered automatically (research R9), so without this a log line
     * written inside the reactive chain would lose the correlation ID.
     */
    @PostConstruct
    void registerMdcAccessor() {
        ContextRegistry.getInstance()
                .registerThreadLocalAccessor(new Slf4jThreadLocalAccessor(CorrelationIds.MDC_KEY));
    }

    @Bean
    CorrelationIdGlobalFilter correlationIdGlobalFilter() {
        return new CorrelationIdGlobalFilter();
    }

    @Bean
    RouteAccessGlobalFilter routeAccessGlobalFilter() {
        return new RouteAccessGlobalFilter(ROUTE_ACCESS_ORDER);
    }

    @Bean
    ClientAddressResolver clientAddressResolver() {
        return new RemoteAddressClientResolver();
    }

    @Bean
    TokenBucketRateLimiter tokenBucketRateLimiter() {
        return rateLimiter;
    }

    @Bean
    RateLimitGlobalFilter rateLimitGlobalFilter(ClientAddressResolver resolver) {
        return new RateLimitGlobalFilter(rateLimiter, resolver, RATE_LIMIT_ORDER);
    }

    @Bean
    GatewayErrorHandler gatewayErrorHandler(ObjectMapper mapper) {
        return new GatewayErrorHandler(mapper);
    }

    /**
     * Drop the buckets when central configuration changes, so a new limit applies at once rather
     * than waiting for the old buckets to be evicted.
     */
    @EventListener(RefreshEvent.class)
    void onConfigurationRefresh(RefreshEvent event) {
        rateLimiter.reset();
    }

    static int correlationOrder() {
        return CORRELATION_ORDER;
    }
}

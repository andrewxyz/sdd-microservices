package com.example.learnhub.gateway.filter;

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
 * Protected by default (constitution Principle IV): a route is public only if it says so, and every
 * other route is refused until the auth spec builds real authentication.
 *
 * <p>Returning 401 rather than 404 for a protected route is deliberate: the route exists, and the
 * caller is being told it needs credentials, which is the answer the future frontend will handle.
 */
public class RouteAccessGlobalFilter implements GlobalFilter, Ordered {

    /** Route metadata key marking a route reachable without credentials. */
    public static final String PUBLIC_METADATA_KEY = "public";

    private final int order;

    public RouteAccessGlobalFilter(int order) {
        this.order = order;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null || isPublic(route.getMetadata())) {
            return chain.filter(exchange);
        }
        return Mono.error(new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "This route requires authentication."));
    }

    /**
     * YAML gives {@code public: true} as a Boolean, but a quoted {@code "true"} is just as natural to
     * write and would silently leave the route protected if only Boolean were accepted.
     */
    static boolean isPublic(Map<String, Object> metadata) {
        if (metadata == null) {
            return false;
        }
        Object value = metadata.get(PUBLIC_METADATA_KEY);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value instanceof String text && Boolean.parseBoolean(text.trim());
    }
}

package com.example.learnhub.gateway.filter;

import com.example.learnhub.gateway.security.IdentityHeaders;
import com.example.learnhub.gateway.security.JwtVerifier;
import com.example.learnhub.gateway.security.VerifiedToken;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Protected by default (constitution Principle IV): a route is public only if it says so (unchanged
 * from platform-foundation's {@code RouteAccessGlobalFilter}, which this supersedes now that real
 * tokens exist to check). Every other route requires a valid access token, and a route whose metadata
 * lists {@code roles} additionally requires the token's role to be one of them (FR-016 to FR-020,
 * research.md R4, R5).
 */
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    /** Route metadata key marking a route reachable without credentials (unchanged from platform-foundation). */
    public static final String PUBLIC_METADATA_KEY = "public";
    /** Route metadata key restricting a protected route to specific roles (research.md R5). */
    public static final String ROLES_METADATA_KEY = "roles";

    private final JwtVerifier verifier;
    private final int order;

    public JwtAuthenticationGlobalFilter(JwtVerifier verifier, int order) {
        this.verifier = verifier;
        this.order = order;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        ServerHttpRequest strippedRequest = IdentityHeaders.stripFrom(exchange.getRequest());

        // No matched route should not reach a global filter in practice, but if it ever did, failing
        // open here would make a routing bug into a security hole (same reasoning as platform-foundation).
        if (route != null && isPublic(route.getMetadata())) {
            return chain.filter(exchange.mutate().request(strippedRequest).build());
        }

        String token = bearerToken(strippedRequest.getHeaders());
        VerifiedToken verified = verifier.verify(token);
        if (!verified.isValid()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "This route requires authentication."));
        }

        List<String> requiredRoles = rolesOf(route != null ? route.getMetadata() : null);
        if (!requiredRoles.isEmpty() && !requiredRoles.contains(verified.role())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Your role does not meet this route's access requirement."));
        }

        ServerHttpRequest authenticatedRequest = strippedRequest.mutate()
                .headers(headers -> {
                    headers.remove(HttpHeaders.AUTHORIZATION);
                    headers.set(IdentityHeaders.USER_ID, verified.accountId());
                    headers.set(IdentityHeaders.USER_ROLE, verified.role());
                })
                .build();
        return chain.filter(exchange.mutate().request(authenticatedRequest).build());
    }

    private static String bearerToken(HttpHeaders headers) {
        String header = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring("Bearer ".length());
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

    /**
     * YAML gives {@code roles: [ADMINISTRATOR]} as a real list when bound to a typed field, but
     * route metadata binds to {@code Map<String, Object>}, and Spring's relaxed binder has no type
     * hint there to turn an indexed child ({@code metadata.roles[0]=ADMINISTRATOR}) into a
     * {@code List}; it becomes a nested {@code Map} instead, keyed {@code "0"}, {@code "1"}, ... —
     * confirmed empirically against the real config server, not assumed. Both shapes are accepted
     * here so this does not silently stop enforcing roles if a future Spring Cloud version binds it
     * as an actual {@code List} instead.
     */
    private static List<String> rolesOf(Map<String, Object> metadata) {
        if (metadata == null) {
            return List.of();
        }
        Object value = metadata.get(ROLES_METADATA_KEY);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value instanceof Map<?, ?> indexed) {
            return indexed.values().stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}

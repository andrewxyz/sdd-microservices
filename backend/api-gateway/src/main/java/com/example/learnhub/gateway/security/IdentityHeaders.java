package com.example.learnhub.gateway.security;

import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Gateway-side helper around the shared header names (platform-common
 * {@link com.example.learnhub.common.identity.IdentityHeaders}). Stripped from every inbound request
 * before the rest of the filter chain runs, public or protected, so a client cannot forge them
 * (research.md R4).
 */
public final class IdentityHeaders {

    public static final String USER_ID = com.example.learnhub.common.identity.IdentityHeaders.USER_ID;
    public static final String USER_ROLE = com.example.learnhub.common.identity.IdentityHeaders.USER_ROLE;

    private IdentityHeaders() {
    }

    public static ServerHttpRequest stripFrom(ServerHttpRequest request) {
        return request.mutate()
                .headers(headers -> {
                    headers.remove(USER_ID);
                    headers.remove(USER_ROLE);
                })
                .build();
    }
}

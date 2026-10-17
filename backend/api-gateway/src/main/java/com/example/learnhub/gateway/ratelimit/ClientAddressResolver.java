package com.example.learnhub.gateway.ratelimit;

import org.springframework.web.server.ServerWebExchange;

/** How the gateway decides which client a request belongs to, for rate limiting. */
public interface ClientAddressResolver {

    String resolve(ServerWebExchange exchange);
}

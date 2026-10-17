package com.example.learnhub.gateway.ratelimit;

import org.springframework.web.server.ServerWebExchange;

/**
 * Identifies a client by the address the connection actually came from.
 *
 * <p>{@code X-Forwarded-For} is deliberately ignored: nothing in front of this gateway is trusted to
 * set it, so honouring it would let any client pick its own rate-limit bucket by sending a made-up
 * header. The cost, accepted in clarification Q1, is that clients behind one proxy or NAT share one
 * limit.
 */
public class RemoteAddressClientResolver implements ClientAddressResolver {

    static final String UNKNOWN = "unknown";

    @Override
    public String resolve(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            // One shared bucket rather than no limit at all: an unidentifiable client must not be
            // the way to bypass rate limiting.
            return UNKNOWN;
        }
        return remote.getAddress().getHostAddress();
    }
}

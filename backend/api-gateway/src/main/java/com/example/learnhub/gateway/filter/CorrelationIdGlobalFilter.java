package com.example.learnhub.gateway.filter;

import com.example.learnhub.common.correlation.CorrelationIds;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Gives every request a correlation ID, forwards it to the service and returns it to the client
 * (FR-020).
 *
 * <p>Runs first, before route access and rate limiting, so that even a 401 or 429 response carries an
 * ID the caller can quote. The ID also goes into the Reactor context, which is how it reaches log
 * lines written further down the reactive chain (research R9).
 */
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    /** Where other gateway components read the resolved ID from. */
    public static final String ATTRIBUTE = "learnhub.correlationId";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = CorrelationIds.resolve(
                exchange.getRequest().getHeaders().getFirst(CorrelationIds.HEADER));

        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder.header(CorrelationIds.HEADER, correlationId))
                .build();
        mutated.getAttributes().put(ATTRIBUTE, correlationId);
        mutated.getResponse().getHeaders().set(CorrelationIds.HEADER, correlationId);

        return chain.filter(mutated)
                .contextWrite(Context.of(CorrelationIds.MDC_KEY, correlationId));
    }

    /** The correlation ID for this exchange, or a fresh one if the filter has not run yet. */
    public static String of(ServerWebExchange exchange) {
        Object value = exchange.getAttributes().get(ATTRIBUTE);
        return value instanceof String id ? id : CorrelationIds.generate();
    }
}

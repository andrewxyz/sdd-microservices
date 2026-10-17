package com.example.learnhub.gateway.error;

import com.example.learnhub.common.correlation.CorrelationIds;
import com.example.learnhub.gateway.filter.CorrelationIdGlobalFilter;
import com.example.learnhub.gateway.ratelimit.RateLimitGlobalFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ConnectTimeoutException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Renders every gateway error as RFC 7807 problem details with the correlation ID, so a client sees
 * one shape whatever went wrong (SC-004).
 *
 * <p>The status mapping is based on measured defaults (research R3): a timeout already surfaces as
 * 504 and an {@code lb://} route with no instances as 503, but a refused connection surfaces as
 * <strong>500</strong>, which would misreport a dead instance as a gateway bug. Mapping it to 503 is
 * what makes SC-005 true during the window between an instance dying and the registry evicting it.
 */
public class GatewayErrorHandler implements WebExceptionHandler, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final ObjectMapper mapper;

    public GatewayErrorHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int getOrder() {
        // Ahead of Spring Boot's default error handling, which would render its own body.
        return -2;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }

        HttpStatus status = statusFor(error);
        if (status.is5xxServerError()) {
            log.warn("Gateway returning {} for {}: {}", status.value(),
                    exchange.getRequest().getPath().value(), error.toString());
        }

        String correlationId = CorrelationIdGlobalFilter.of(exchange);
        exchange.getResponse().getHeaders().set(CorrelationIds.HEADER, correlationId);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            Object retryAfter = exchange.getAttributes().get(RateLimitGlobalFilter.RETRY_AFTER_ATTRIBUTE);
            long seconds = retryAfter instanceof Long value ? value : 1L;
            exchange.getResponse().getHeaders().set("Retry-After", Long.toString(seconds));
        }

        byte[] body;
        try {
            body = mapper.writeValueAsBytes(GatewayProblem.body(
                    status, exchange.getRequest().getPath().value(), correlationId));
        } catch (Exception serialisationFailure) {
            body = ("{\"type\":\"about:blank\",\"title\":\"Internal Server Error\",\"status\":500,"
                    + "\"correlationId\":\"" + correlationId + "\"}").getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private HttpStatus statusFor(Throwable error) {
        if (error instanceof NotFoundException) {
            // Raised by the load balancer when a service has no healthy instance registered.
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (error instanceof ResponseStatusException statusException) {
            HttpStatus resolved = HttpStatus.resolve(statusException.getStatusCode().value());
            return resolved == null ? HttpStatus.INTERNAL_SERVER_ERROR : resolved;
        }
        if (hasCause(error, ConnectException.class)) {
            // Measured default is 500; a refused connection means the instance is gone, not broken.
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (hasCause(error, ConnectTimeoutException.class)
                || hasCause(error, TimeoutException.class)
                || hasCause(error, io.netty.handler.timeout.ReadTimeoutException.class)) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}

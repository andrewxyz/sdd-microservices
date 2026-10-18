package com.example.learnhub.gateway.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Builds the one error shape every gateway response uses (constitution Principle VIII, FR-025).
 *
 * <p>Contract: {@code specs/001-platform-foundation/contracts/problem-details.schema.json}.
 * {@code detail} is always a fixed phrase per status, never an exception message: upstream failures
 * routinely carry hostnames and connection strings that a client must not see.
 */
public final class GatewayProblem {

    private GatewayProblem() {
    }

    public static Map<String, Object> body(HttpStatus status, String instance, String correlationId) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detailFor(status));
        problem.put("instance", instance);
        problem.put("correlationId", correlationId);
        return problem;
    }

    private static String detailFor(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> "This route requires authentication.";
            case FORBIDDEN -> "Your role does not meet this route's access requirement.";
            case NOT_FOUND -> "No route matches this path.";
            case TOO_MANY_REQUESTS -> "Rate limit exceeded for this client address.";
            case SERVICE_UNAVAILABLE -> "No healthy instance of the target service is available.";
            case GATEWAY_TIMEOUT -> "The target service did not respond in time.";
            default -> "The request could not be completed.";
        };
    }
}

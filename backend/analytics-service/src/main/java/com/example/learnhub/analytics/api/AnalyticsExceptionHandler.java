package com.example.learnhub.analytics.api;

import com.example.learnhub.analytics.domain.InvalidParameterException;
import com.example.learnhub.analytics.infrastructure.OwnershipUnavailableException;
import com.example.learnhub.common.correlation.CorrelationIds;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * The two errors specific to this service, as RFC 7807 problem details with a {@code correlationId}
 * (constitution VIII). Ordered ahead of the platform's catch-all advice so these win over its generic 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AnalyticsExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsExceptionHandler.class);

    /** Fail closed (spec FR-016): no figures at all, just the reference code. */
    @ExceptionHandler(OwnershipUnavailableException.class)
    public ResponseEntity<ProblemDetail> ownershipUnavailable(OwnershipUnavailableException exception,
            WebRequest request) {
        log.warn("Dashboard request refused because course ownership could not be confirmed: {}",
                exception.getMessage());
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, request);
        problem.setDetail("The dashboard data is temporarily unavailable. Please try again shortly.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @ExceptionHandler(InvalidParameterException.class)
    public ResponseEntity<ProblemDetail> invalidParameter(InvalidParameterException exception, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, request);
        problem.setDetail(exception.getMessage());
        problem.setProperty("fieldErrors", Map.of(exception.field(), exception.getMessage()));
        return ResponseEntity.badRequest().body(problem);
    }

    private ProblemDetail problem(HttpStatus status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(status.getReasonPhrase());
        String correlationId = MDC.get(CorrelationIds.MDC_KEY);
        problem.setProperty("correlationId", correlationId != null ? correlationId : CorrelationIds.generate());
        String description = request.getDescription(false);
        if (description != null && description.startsWith("uri=")) {
            problem.setInstance(URI.create(description.substring(4)));
        }
        return problem;
    }
}

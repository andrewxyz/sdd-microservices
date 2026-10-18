package com.example.learnhub.common.error;

import com.example.learnhub.common.correlation.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error from a servlet service into RFC 7807 problem details carrying the correlation ID
 * (constitution Principle VIII), so the future frontend can handle all failures one way.
 *
 * <p>Contract: {@code schemas/problem-details.schema.json} on the classpath.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException exception,
            WebRequest request) {
        HttpStatusCode status = exception.getStatusCode();
        return ResponseEntity.status(status)
                .body(problem(status, HttpStatus.valueOf(status.value()).getReasonPhrase(), request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, WebRequest request) {
        // Log the cause in full, but never put it in the body: it can carry connection strings and
        // other internals that a client must not see.
        log.error("Unhandled exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", request));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(status, HttpStatus.valueOf(status.value()).getReasonPhrase(), request);
        return new ResponseEntity<>(problem, headers, status);
    }

    private ProblemDetail problem(HttpStatusCode status, String title, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(title);
        problem.setProperty("correlationId", currentCorrelationId());

        String description = request.getDescription(false);
        if (description != null && description.startsWith("uri=")) {
            problem.setInstance(java.net.URI.create(description.substring(4)));
        }
        return problem;
    }

    private String currentCorrelationId() {
        String fromMdc = MDC.get(CorrelationIds.MDC_KEY);
        // Falling back to a generated id keeps the contract's "always present" promise even if the
        // failure happened before or outside the correlation filter.
        return fromMdc != null ? fromMdc : CorrelationIds.generate();
    }
}

package com.example.learnhub.common.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a correlation ID on every request, in MDC (so structured logs carry it without per-service
 * code) and on the response (so the caller can quote it).
 *
 * <p>Runs first: anything that logs or fails later must already have the ID available.
 */
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER));

        MDC.put(CorrelationIds.MDC_KEY, correlationId);
        response.setHeader(CorrelationIds.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Always clear: threads are pooled, so a leaked value would mislabel a later request.
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }
}

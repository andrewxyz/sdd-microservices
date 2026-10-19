package com.example.learnhub.enrollment.infrastructure;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Calls course-service's existing internal lookup (specs/003-course-catalog, extended in
 * specs/005-progress-certificates-notifications research.md R3), never through the gateway. Timeouts,
 * circuit breaker and fallback per constitution Principle V (research.md R5).
 *
 * <p>{@code url} is empty in production, so the client load-balances via {@code lb://course-service} as
 * usual; tests override {@code course-service.url} directly (not
 * {@code spring.cloud.openfeign.client.config.course-service.url}, which Spring Cloud LoadBalancer does
 * not reliably honor, research.md finding). Setting {@code url} here makes
 * {@link org.springframework.cloud.openfeign.FeignClientFactoryBean} build a plain, non-load-balanced
 * target, deterministically bypassing the load balancer.
 */
@FeignClient(name = "course-service", url = "${course-service.url:}",
        fallbackFactory = CourseLookupClientFallbackFactory.class)
public interface CourseLookupClient {

    @GetMapping("/api/v1/internal/courses/{courseId}")
    CourseSummaryResponse summary(@PathVariable("courseId") UUID courseId);
}

package com.example.learnhub.notification.infrastructure;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Calls course-service's internal lookup to get a course's title (research.md R3, R5), never through
 * the gateway. notification-service's own copy, per the one-Feign-client-per-consumer convention
 * already established in 004.
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

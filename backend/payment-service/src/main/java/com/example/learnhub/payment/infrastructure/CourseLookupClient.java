package com.example.learnhub.payment.infrastructure;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Calls course-service's existing internal lookup (specs/003-course-catalog FR-023), never through the
 * gateway. Timeouts, circuit breaker and fallback per constitution Principle V (research.md R4).
 */
@FeignClient(name = "course-service", fallbackFactory = CourseLookupClientFallbackFactory.class)
public interface CourseLookupClient {

    @GetMapping("/api/v1/internal/courses/{courseId}")
    CourseSummaryResponse summary(@PathVariable("courseId") UUID courseId);
}

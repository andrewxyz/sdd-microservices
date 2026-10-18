package com.example.learnhub.course.infrastructure;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Calls enrollment-service's internal lookup (specs/004-checkout-enrollment research.md R4, R5), never
 * through the gateway. Timeouts, circuit breaker and fallback per constitution Principle V.
 */
@FeignClient(name = "enrollment-service", fallback = EnrollmentLookupClientFallback.class)
public interface EnrollmentLookupClient {

    @GetMapping("/api/v1/internal/enrollments/{courseId}/{userId}")
    EnrollmentLookupResponse lookup(@PathVariable("courseId") UUID courseId, @PathVariable("userId") UUID userId);
}

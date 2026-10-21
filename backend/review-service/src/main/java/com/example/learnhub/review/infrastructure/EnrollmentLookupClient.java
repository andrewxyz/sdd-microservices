package com.example.learnhub.review.infrastructure;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Calls enrollment-service's existing, unmodified internal lookup endpoint (research.md R2), never
 * through the gateway — byte-for-byte the shape of course-service's own {@code EnrollmentLookupClient}.
 * Timeouts, circuit breaker and fallback per constitution Principle V.
 */
@FeignClient(name = "enrollment-service", fallback = EnrollmentLookupClientFallback.class)
public interface EnrollmentLookupClient {

    @GetMapping("/api/v1/internal/enrollments/{courseId}/{userId}")
    EnrollmentLookupResponse lookup(@PathVariable("courseId") UUID courseId, @PathVariable("userId") UUID userId);
}

package com.example.learnhub.analytics.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Asks course-service who owns which course, at query time only (research R1; constitution XIV forbids
 * doing this while handling an event). Never goes through the gateway: no route exists for these paths.
 *
 * <p>{@code url} is empty in production, so the client load-balances via {@code lb://course-service};
 * tests set {@code course-service.url} to a stub, which builds a plain target and bypasses the load
 * balancer (the same arrangement notification-service uses).
 */
@FeignClient(name = "course-service", url = "${course-service.url:}",
        fallbackFactory = CourseOwnershipClientFallbackFactory.class)
public interface CourseOwnershipClient {

    /** Up to 200 courses of one instructor, every status, newest first (specs/009 contract). */
    @GetMapping("/api/v1/internal/courses")
    List<InstructorCourseItem> listByInstructor(@RequestParam("instructorId") UUID instructorId);

    /** The existing internal lookup (specs/005 contract); only the owner is read. */
    @GetMapping("/api/v1/internal/courses/{courseId}")
    CourseOwner detail(@PathVariable("courseId") UUID courseId);
}

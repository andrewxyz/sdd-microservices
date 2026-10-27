package com.example.learnhub.course.api;

import com.example.learnhub.course.application.CourseLookupService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/internal/courses/{courseId}} — FR-023. No gateway route targets this path
 * (backend/config-repo/api-gateway.yml deliberately omits one, research.md R6); reachable only by
 * another backend service calling {@code lb://course-service} directly.
 */
@RestController
@RequestMapping("/api/v1/internal/courses")
public class InternalCourseController {

    private final CourseLookupService courseLookupService;

    public InternalCourseController(CourseLookupService courseLookupService) {
        this.courseLookupService = courseLookupService;
    }

    /**
     * {@code GET /api/v1/internal/courses?instructorId=} (spec 009): the instructor's courses for the
     * dashboard, any status, newest first, at most 200. Like the lookup above, no gateway route reaches it.
     */
    @GetMapping
    public List<InstructorCourseItem> byInstructor(@RequestParam UUID instructorId) {
        return courseLookupService.listByInstructor(instructorId);
    }

    @GetMapping("/{courseId}")
    public CourseSummaryView summary(@PathVariable UUID courseId) {
        return courseLookupService.summarize(courseId);
    }
}

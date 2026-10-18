package com.example.learnhub.course.api;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.course.application.CourseContentService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/courses/{courseId}/content} — enrolled student, owner, or administrator only
 * (specs/004-checkout-enrollment FR-021 to FR-024, research.md R5).
 */
@RestController
@RequestMapping("/api/v1/courses")
public class CourseContentController {

    private final CourseContentService courseContentService;

    public CourseContentController(CourseContentService courseContentService) {
        this.courseContentService = courseContentService;
    }

    @GetMapping("/{courseId}/content")
    public CourseContentView content(@PathVariable UUID courseId,
            @RequestHeader(IdentityHeaders.USER_ID) UUID callerId,
            @RequestHeader(IdentityHeaders.USER_ROLE) String callerRole) {
        return courseContentService.view(courseId, callerId, callerRole);
    }
}

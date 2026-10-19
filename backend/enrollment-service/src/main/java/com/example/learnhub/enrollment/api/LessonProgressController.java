package com.example.learnhub.enrollment.api;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.enrollment.application.LessonProgressService;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PUT}/{@code DELETE .../lessons/{lessonId}/complete} and {@code GET .../progress} — protected,
 * falling under the gateway's existing {@code enrollments} route (research.md R9). FR-001 to FR-007.
 */
@RestController
@RequestMapping("/api/v1/enrollments/{courseId}")
public class LessonProgressController {

    private final LessonProgressService lessonProgressService;

    public LessonProgressController(LessonProgressService lessonProgressService) {
        this.lessonProgressService = lessonProgressService;
    }

    @PutMapping("/lessons/{lessonId}/complete")
    public ProgressView markComplete(@RequestHeader(IdentityHeaders.USER_ID) UUID userId,
            @PathVariable UUID courseId, @PathVariable UUID lessonId) {
        return lessonProgressService.mark(userId, courseId, lessonId);
    }

    @DeleteMapping("/lessons/{lessonId}/complete")
    public ProgressView unmarkComplete(@RequestHeader(IdentityHeaders.USER_ID) UUID userId,
            @PathVariable UUID courseId, @PathVariable UUID lessonId) {
        return lessonProgressService.unmark(userId, courseId, lessonId);
    }

    @GetMapping("/progress")
    public ProgressView progress(@RequestHeader(IdentityHeaders.USER_ID) UUID userId,
            @PathVariable UUID courseId) {
        return lessonProgressService.view(userId, courseId);
    }
}

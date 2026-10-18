package com.example.learnhub.course.api;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.course.application.CourseFullQueryService;
import com.example.learnhub.course.infrastructure.ConcurrencyControl;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/courses/{courseId}/full} — owner or administrator only (FR-024, US5). */
@RestController
@RequestMapping("/api/v1/courses")
public class CourseFullController {

    private final CourseFullQueryService courseFullQueryService;

    public CourseFullController(CourseFullQueryService courseFullQueryService) {
        this.courseFullQueryService = courseFullQueryService;
    }

    @GetMapping("/{courseId}/full")
    public ResponseEntity<CourseFullView> full(@PathVariable UUID courseId,
            @RequestHeader(IdentityHeaders.USER_ID) UUID callerId,
            @RequestHeader(IdentityHeaders.USER_ROLE) String callerRole) {
        CourseFullView view = courseFullQueryService.view(courseId, callerId, callerRole);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ConcurrencyControl.etagOf(view.version()))
                .body(view);
    }
}

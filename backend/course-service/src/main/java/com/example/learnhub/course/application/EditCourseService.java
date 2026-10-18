package com.example.learnhub.course.application;

import com.example.learnhub.course.api.CourseFullView;
import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.CourseStatus;
import com.example.learnhub.course.domain.Level;
import com.example.learnhub.course.domain.PublishReadiness;
import com.example.learnhub.course.infrastructure.ConcurrencyControl;
import com.example.learnhub.course.infrastructure.CourseRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * FR-010, FR-011, FR-018, FR-025: only the owner or an administrator may edit a course, only with a
 * current {@code If-Match}, and never into a state that would no longer meet FR-014 if the course is
 * already published (spec.md Clarifications 2026-10-18: refused outright, never silently demoted).
 */
@Service
public class EditCourseService {

    private final CourseRepository courses;
    private final OwnershipGuard ownershipGuard;

    public EditCourseService(CourseRepository courses, OwnershipGuard ownershipGuard) {
        this.courses = courses;
        this.ownershipGuard = ownershipGuard;
    }

    public record Request(String title, String description, UUID categoryId, Level level, String language,
            Integer priceCents) {
    }

    @Transactional
    public CourseFullView edit(UUID courseId, Request request, String ifMatch, UUID callerId, String callerRole) {
        Course course = courses.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such course."));
        ownershipGuard.requireOwnerOrAdmin(course, callerId, callerRole);
        ConcurrencyControl.requireMatch(ifMatch, course.getVersion());

        course.setTitle(request.title());
        course.setDescription(request.description());
        course.setCategoryId(request.categoryId());
        course.setLevel(request.level());
        course.setLanguage(request.language());
        course.setPriceCents(request.priceCents());

        if (course.getStatus() == CourseStatus.PUBLISHED) {
            List<String> violations = PublishReadiness.violations(course);
            if (!violations.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This edit would leave the published course incomplete: " + String.join(", ", violations));
            }
        }

        course.touch(Instant.now());
        return CourseFullView.of(courses.save(course));
    }
}

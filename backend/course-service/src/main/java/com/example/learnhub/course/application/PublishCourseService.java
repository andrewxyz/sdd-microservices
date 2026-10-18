package com.example.learnhub.course.application;

import com.example.learnhub.course.api.CourseFullView;
import com.example.learnhub.course.domain.Course;
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
 * FR-014, FR-015, FR-017: publishes a draft, or republishes an archived course, only when
 * {@link PublishReadiness} is satisfied; every publish (first or repeat) refreshes {@code publishedAt}
 * (research.md R5).
 */
@Service
public class PublishCourseService {

    private final CourseRepository courses;
    private final OwnershipGuard ownershipGuard;

    public PublishCourseService(CourseRepository courses, OwnershipGuard ownershipGuard) {
        this.courses = courses;
        this.ownershipGuard = ownershipGuard;
    }

    @Transactional
    public CourseFullView publish(UUID courseId, String ifMatch, UUID callerId, String callerRole) {
        Course course = courses.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such course."));
        ownershipGuard.requireOwnerOrAdmin(course, callerId, callerRole);
        ConcurrencyControl.requireMatch(ifMatch, course.getVersion());

        List<String> violations = PublishReadiness.violations(course);
        if (!violations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Not ready to publish: " + String.join(", ", violations));
        }

        course.markPublished(Instant.now());
        return CourseFullView.of(courses.save(course));
    }
}

package com.example.learnhub.course.application;

import com.example.learnhub.course.api.CourseFullView;
import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.infrastructure.CourseRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * FR-024, US5: the owner/administrator view, including every lesson's content. {@link OwnershipGuard}
 * is what actually enforces FR-024 and FR-011 here; this service has no privacy logic of its own.
 */
@Service
public class CourseFullQueryService {

    private final CourseRepository courses;
    private final OwnershipGuard ownershipGuard;

    public CourseFullQueryService(CourseRepository courses, OwnershipGuard ownershipGuard) {
        this.courses = courses;
        this.ownershipGuard = ownershipGuard;
    }

    @Transactional(readOnly = true)
    public CourseFullView view(UUID courseId, UUID callerId, String callerRole) {
        Course course = courses.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such course."));
        ownershipGuard.requireOwnerOrAdmin(course, callerId, callerRole);
        return CourseFullView.of(course);
    }
}

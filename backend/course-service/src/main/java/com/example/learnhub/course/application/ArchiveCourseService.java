package com.example.learnhub.course.application;

import com.example.learnhub.course.api.CourseFullView;
import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.infrastructure.ConcurrencyControl;
import com.example.learnhub.course.infrastructure.CourseRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** FR-016: archives a published course; it stays intact, just hidden from the public catalog. */
@Service
public class ArchiveCourseService {

    private final CourseRepository courses;
    private final OwnershipGuard ownershipGuard;

    public ArchiveCourseService(CourseRepository courses, OwnershipGuard ownershipGuard) {
        this.courses = courses;
        this.ownershipGuard = ownershipGuard;
    }

    @Transactional
    public CourseFullView archive(UUID courseId, String ifMatch, UUID callerId, String callerRole) {
        Course course = courses.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such course."));
        ownershipGuard.requireOwnerOrAdmin(course, callerId, callerRole);
        ConcurrencyControl.requireMatch(ifMatch, course.getVersion());

        course.markArchived(Instant.now());
        return CourseFullView.of(courses.save(course));
    }
}

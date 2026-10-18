package com.example.learnhub.course.application;

import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.CourseStatus;
import com.example.learnhub.course.infrastructure.CourseRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** FR-013: only a draft may be deleted, only by its owner or an administrator. */
@Service
public class DeleteCourseService {

    private final CourseRepository courses;
    private final OwnershipGuard ownershipGuard;

    public DeleteCourseService(CourseRepository courses, OwnershipGuard ownershipGuard) {
        this.courses = courses;
        this.ownershipGuard = ownershipGuard;
    }

    @Transactional
    public void delete(UUID courseId, UUID callerId, String callerRole) {
        Course course = courses.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such course."));
        ownershipGuard.requireOwnerOrAdmin(course, callerId, callerRole);
        if (course.getStatus() != CourseStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a draft course can be deleted.");
        }
        courses.delete(course);
    }
}

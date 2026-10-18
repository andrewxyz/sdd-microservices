package com.example.learnhub.course.application;

import com.example.learnhub.course.api.CoursePublicView;
import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.CourseStatus;
import com.example.learnhub.course.infrastructure.CourseRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * FR-006, FR-007: the public single-course view. A draft, an archived course, and an unknown id all
 * produce the identical "not found" response — this service never treats the caller as the owner or
 * an administrator; that distinction is made by the full-detail endpoint instead.
 */
@Service
public class CourseDetailService {

    private final CourseRepository courses;

    public CourseDetailService(CourseRepository courses) {
        this.courses = courses;
    }

    @Transactional(readOnly = true)
    public CoursePublicView viewPublic(UUID courseId) {
        Course course = courses.findById(courseId)
                .orElseThrow(CourseDetailService::notFound);
        if (course.getStatus() != CourseStatus.PUBLISHED) {
            throw notFound();
        }
        return CoursePublicView.of(course);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such course.");
    }
}

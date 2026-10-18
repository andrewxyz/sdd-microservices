package com.example.learnhub.course.application;

import com.example.learnhub.course.api.CourseFullView;
import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.Level;
import com.example.learnhub.course.infrastructure.CourseRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * FR-008, FR-009: creates a draft owned by the caller. A student is refused outright; an instructor or
 * an administrator becomes the course's owning instructor of record (spec.md Clarifications 2026-10-18).
 */
@Service
public class CreateCourseService {

    private final CourseRepository courses;

    public CreateCourseService(CourseRepository courses) {
        this.courses = courses;
    }

    public record Request(String title, String description, UUID categoryId, Level level, String language,
            Integer priceCents) {
    }

    @Transactional
    public CourseFullView create(Request request, UUID callerId, String callerRole) {
        if ("STUDENT".equals(callerRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Students cannot create courses.");
        }

        Course course = new Course(UUID.randomUUID(), callerId, Instant.now());
        course.setTitle(request.title());
        course.setDescription(request.description());
        course.setCategoryId(request.categoryId());
        course.setLevel(request.level());
        course.setLanguage(request.language());
        course.setPriceCents(request.priceCents());

        return CourseFullView.of(courses.save(course));
    }
}

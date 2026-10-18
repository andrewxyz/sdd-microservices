package com.example.learnhub.course.application;

import com.example.learnhub.course.api.CourseContentView;
import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.CourseStatus;
import com.example.learnhub.course.domain.Lesson;
import com.example.learnhub.course.infrastructure.CourseRepository;
import com.example.learnhub.course.infrastructure.EnrollmentLookupClient;
import com.example.learnhub.course.infrastructure.EnrollmentLookupResponse;
import com.example.learnhub.course.infrastructure.LessonRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /api/v1/courses/{id}/content} (specs/004-checkout-enrollment FR-021 to FR-024,
 * research.md R5): a reading view, distinct from 003's owner/admin editing view ({@code /full}). The
 * owning instructor and administrators always see it; anyone else must be a confirmed enrollment
 * (checked via {@link EnrollmentLookupClient}), never shown on an unconfirmed basis (FR-024).
 */
@Service
public class CourseContentService {

    private static final String ADMINISTRATOR = "ADMINISTRATOR";

    private final CourseRepository courses;
    private final LessonRepository lessons;
    private final EnrollmentLookupClient enrollmentLookupClient;

    public CourseContentService(CourseRepository courses, LessonRepository lessons,
            EnrollmentLookupClient enrollmentLookupClient) {
        this.courses = courses;
        this.lessons = lessons;
        this.enrollmentLookupClient = enrollmentLookupClient;
    }

    @Transactional(readOnly = true)
    public CourseContentView view(UUID courseId, UUID callerId, String callerRole) {
        Course course = courses.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such course."));

        if (!isOwnerOrAdmin(course, callerId, callerRole)) {
            if (course.getStatus() != CourseStatus.PUBLISHED) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such course.");
            }
            EnrollmentLookupResponse lookup = enrollmentLookupClient.lookup(courseId, callerId);
            if (!lookup.enrolled()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You are not enrolled in this course.");
            }
        }

        List<Lesson> courseLessons = lessons.findByCourseIdOrderByPosition(courseId);
        return CourseContentView.of(course, courseLessons);
    }

    private boolean isOwnerOrAdmin(Course course, UUID callerId, String callerRole) {
        return ADMINISTRATOR.equals(callerRole) || course.getInstructorId().equals(callerId);
    }
}

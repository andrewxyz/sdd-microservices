package com.example.learnhub.course.application;

import com.example.learnhub.course.api.CourseSummaryView;
import com.example.learnhub.course.api.InstructorCourseItem;
import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.Lesson;
import com.example.learnhub.course.infrastructure.CourseRepository;
import com.example.learnhub.course.infrastructure.LessonRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * FR-023, research.md R6: the service-to-service lookup, reached directly (never through the gateway,
 * which has no route for this path at all) by another backend service via {@code lb://course-service}.
 */
@Service
public class CourseLookupService {

    private final CourseRepository courses;
    private final LessonRepository lessons;

    public CourseLookupService(CourseRepository courses, LessonRepository lessons) {
        this.courses = courses;
        this.lessons = lessons;
    }

    @Transactional(readOnly = true)
    public CourseSummaryView summarize(UUID courseId) {
        Course course = courses.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such course."));
        List<Lesson> orderedLessons = lessons.findByCourseIdOrderByPosition(courseId);
        return CourseSummaryView.of(course, orderedLessons.size(), orderedLessons);
    }

    /** Spec 009: an instructor's courses in every status, newest first, at most 200. */
    @Transactional(readOnly = true)
    public List<InstructorCourseItem> listByInstructor(UUID instructorId) {
        return courses.findTop200ByInstructorIdOrderByCreatedAtDesc(instructorId).stream()
                .map(InstructorCourseItem::of)
                .toList();
    }
}

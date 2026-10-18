package com.example.learnhub.course.application;

import com.example.learnhub.course.api.LessonResponse;
import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.CourseStatus;
import com.example.learnhub.course.domain.Lesson;
import com.example.learnhub.course.domain.PublishReadiness;
import com.example.learnhub.course.infrastructure.ConcurrencyControl;
import com.example.learnhub.course.infrastructure.CourseRepository;
import com.example.learnhub.course.infrastructure.LessonRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * FR-010, FR-011, FR-018, FR-025: add, edit and remove a course's lessons. Every change also touches
 * the parent {@link Course}, which is what bumps its optimistic-lock version (research.md R1) — a
 * lesson has no version of its own, since it is not an independent save boundary (data-model.md).
 */
@Service
public class LessonService {

    private final CourseRepository courses;
    private final LessonRepository lessons;
    private final OwnershipGuard ownershipGuard;

    public LessonService(CourseRepository courses, LessonRepository lessons, OwnershipGuard ownershipGuard) {
        this.courses = courses;
        this.lessons = lessons;
        this.ownershipGuard = ownershipGuard;
    }

    public record Request(String title, int durationMinutes, String content) {
    }

    @Transactional
    public LessonResponse add(UUID courseId, Request request, String ifMatch, UUID callerId, String callerRole) {
        Course course = findAndAuthorize(courseId, ifMatch, callerId, callerRole);
        int nextPosition = course.getLessons().stream().mapToInt(Lesson::getPosition).max().orElse(-1) + 1;
        Lesson lesson = new Lesson(UUID.randomUUID(), course, request.title(), nextPosition,
                request.durationMinutes(), request.content());
        lessons.save(lesson);
        course.touch(Instant.now());
        courses.save(course);
        return LessonResponse.of(lesson);
    }

    @Transactional
    public LessonResponse edit(UUID courseId, UUID lessonId, Request request, String ifMatch, UUID callerId,
            String callerRole) {
        Course course = findAndAuthorize(courseId, ifMatch, callerId, callerRole);
        Lesson lesson = lessonOf(course, lessonId);
        lesson.setTitle(request.title());
        lesson.setDurationMinutes(request.durationMinutes());
        lesson.setContent(request.content());
        course.touch(Instant.now());
        courses.save(course);
        return LessonResponse.of(lesson);
    }

    @Transactional
    public void remove(UUID courseId, UUID lessonId, String ifMatch, UUID callerId, String callerRole) {
        Course course = findAndAuthorize(courseId, ifMatch, callerId, callerRole);
        Lesson lesson = lessonOf(course, lessonId);

        if (course.getStatus() == CourseStatus.PUBLISHED && course.getLessons().size() == 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This would leave the published course with no lessons: "
                            + String.join(", ", PublishReadiness.violations(course)));
        }

        course.getLessons().remove(lesson);
        lessons.delete(lesson);
        renumberContiguously(course);
        course.touch(Instant.now());
        courses.save(course);
    }

    private void renumberContiguously(Course course) {
        List<Lesson> ordered = course.getLessons().stream()
                .sorted(Comparator.comparingInt(Lesson::getPosition))
                .toList();
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setPosition(i);
        }
    }

    private Course findAndAuthorize(UUID courseId, String ifMatch, UUID callerId, String callerRole) {
        Course course = courses.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such course."));
        ownershipGuard.requireOwnerOrAdmin(course, callerId, callerRole);
        ConcurrencyControl.requireMatch(ifMatch, course.getVersion());
        return course;
    }

    private static Lesson lessonOf(Course course, UUID lessonId) {
        return course.getLessons().stream()
                .filter(lesson -> lesson.getId().equals(lessonId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such lesson."));
    }
}

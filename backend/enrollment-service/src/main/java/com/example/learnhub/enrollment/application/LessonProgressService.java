package com.example.learnhub.enrollment.application;

import com.example.learnhub.enrollment.api.ProgressView;
import com.example.learnhub.enrollment.domain.Enrollment;
import com.example.learnhub.enrollment.domain.LessonProgress;
import com.example.learnhub.enrollment.domain.OutboxEvent;
import com.example.learnhub.enrollment.infrastructure.CourseLookupClient;
import com.example.learnhub.enrollment.infrastructure.CourseSummaryResponse;
import com.example.learnhub.enrollment.infrastructure.EnrollmentRepository;
import com.example.learnhub.enrollment.infrastructure.LessonProgressRepository;
import com.example.learnhub.enrollment.infrastructure.OutboxEventRepository;
import com.example.learnhub.events.CourseCompletedPayload;
import com.example.learnhub.events.EventMapper;
import com.example.learnhub.events.LearningEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lesson progress and one-time course completion (FR-001 to FR-009, research.md R1).
 */
@Service
public class LessonProgressService {

    private static final String FORBIDDEN_MESSAGE = "You are not enrolled in this course.";

    private final EnrollmentRepository enrollments;
    private final LessonProgressRepository progress;
    private final OutboxEventRepository outboxEvents;
    private final CourseLookupClient courseLookupClient;
    private final ObjectMapper eventMapper = EventMapper.create();

    public LessonProgressService(EnrollmentRepository enrollments, LessonProgressRepository progress,
            OutboxEventRepository outboxEvents, CourseLookupClient courseLookupClient) {
        this.enrollments = enrollments;
        this.progress = progress;
        this.outboxEvents = outboxEvents;
        this.courseLookupClient = courseLookupClient;
    }

    @Transactional
    public ProgressView mark(UUID userId, UUID courseId, UUID lessonId) {
        // research.md R1: hold a pessimistic write lock on the enrollment for the rest of this
        // transaction, so the progress count recomputed below is always accurate as of this moment —
        // not racing a concurrent sibling's own insert into the same course's progress.
        Enrollment enrollment = enrollments.findByUserIdAndCourseIdForUpdate(userId, courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, FORBIDDEN_MESSAGE));
        CourseSummaryResponse course = courseLookupClient.summary(courseId);
        requireLessonBelongsToCourse(course, lessonId);

        if (!progress.existsByEnrollmentIdAndLessonId(enrollment.getId(), lessonId)) {
            progress.save(new LessonProgress(UUID.randomUUID(), enrollment.getId(), lessonId, Instant.now()));
        }

        Instant completedAt = maybeCompleteCourse(enrollment, course);

        return buildView(enrollment, course, completedAt);
    }

    /**
     * research.md R1: only the caller whose conditional update actually changes a row (affected-row
     * count 1) writes the {@code course.completed} outbox row — the mechanism that keeps ten
     * concurrent completions of a course's last few lessons down to exactly one event (FR-008).
     *
     * <p>Returns the enrollment's completion instant for this request's own response: the existing one
     * if already completed, the instant just claimed if this call won the race, or {@code null}
     * otherwise. The conditional {@code UPDATE} below never refreshes the in-memory {@code enrollment}
     * instance — {@link Enrollment} deliberately exposes no setter for {@code completedAt} — so reading
     * {@code enrollment.getCompletedAt()} straight after it would still see the pre-update {@code null}.
     */
    private Instant maybeCompleteCourse(Enrollment enrollment, CourseSummaryResponse course) {
        if (enrollment.getCompletedAt() != null) {
            return enrollment.getCompletedAt();
        }
        long completed = progress.countByEnrollmentId(enrollment.getId());
        if (completed < course.lessonCount()) {
            return null;
        }
        Instant now = Instant.now();
        int updated = enrollments.markCompletedIfNotAlready(enrollment.getId(), now);
        if (updated != 1) {
            return null;
        }
        CourseCompletedPayload payload = new CourseCompletedPayload(enrollment.getId(),
                enrollment.getUserId(), enrollment.getCourseId(), now);
        String json;
        try {
            json = eventMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize course.completed payload", e);
        }
        outboxEvents.save(new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), enrollment.getId(),
                LearningEvents.COURSE_COMPLETED, json, now));
        return now;
    }

    @Transactional
    public ProgressView unmark(UUID userId, UUID courseId, UUID lessonId) {
        Enrollment enrollment = requireEnrollment(userId, courseId);
        progress.deleteByEnrollmentIdAndLessonId(enrollment.getId(), lessonId);
        CourseSummaryResponse course = courseLookupClient.summary(courseId);
        return buildView(enrollment, course);
    }

    @Transactional(readOnly = true)
    public ProgressView view(UUID userId, UUID courseId) {
        Enrollment enrollment = requireEnrollment(userId, courseId);
        CourseSummaryResponse course = courseLookupClient.summary(courseId);
        return buildView(enrollment, course);
    }

    private Enrollment requireEnrollment(UUID userId, UUID courseId) {
        return enrollments.findByUserIdAndCourseId(userId, courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, FORBIDDEN_MESSAGE));
    }

    private void requireLessonBelongsToCourse(CourseSummaryResponse course, UUID lessonId) {
        if (course.lessonIds() == null || !course.lessonIds().contains(lessonId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such lesson in this course.");
        }
    }

    ProgressView buildView(Enrollment enrollment, CourseSummaryResponse course) {
        return buildView(enrollment, course, enrollment.getCompletedAt());
    }

    private ProgressView buildView(Enrollment enrollment, CourseSummaryResponse course, Instant completedAt) {
        long completed = progress.countByEnrollmentId(enrollment.getId());
        long total = course.lessonCount();
        double percent = total == 0 ? 0.0 : (completed * 100.0) / total;
        return new ProgressView(enrollment.getCourseId(), completed, total, percent, completedAt);
    }
}

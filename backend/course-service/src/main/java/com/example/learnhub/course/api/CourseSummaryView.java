package com.example.learnhub.course.api;

import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.Lesson;
import java.util.List;
import java.util.UUID;

/**
 * FR-023, research.md R6: the service-to-service lookup response, regardless of course status.
 * {@code title} and {@code lessonIds} were added in specs/005-progress-certificates-notifications
 * (research.md R3) — the endpoint path and every pre-existing field are unchanged, so
 * payment-service's existing Feign client (004) keeps working against this shape unmodified.
 */
public record CourseSummaryView(UUID courseId, String status, Integer priceCents, String currency,
        UUID instructorId, long lessonCount, String title, List<UUID> lessonIds) {

    /** research.md R3: every lesson id of the course, in position order, capped at 200. */
    private static final int MAX_LESSON_IDS = 200;

    public static CourseSummaryView of(Course course, long lessonCount, List<Lesson> orderedLessons) {
        List<UUID> lessonIds = orderedLessons.stream()
                .map(Lesson::getId)
                .limit(MAX_LESSON_IDS)
                .toList();
        return new CourseSummaryView(course.getId(), course.getStatus().name(), course.getPriceCents(),
                course.getCurrency(), course.getInstructorId(), lessonCount, course.getTitle(), lessonIds);
    }
}

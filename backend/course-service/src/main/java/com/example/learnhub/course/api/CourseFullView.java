package com.example.learnhub.course.api;

import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.Lesson;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The owner/administrator view: every public field plus {@code status}, {@code version} (also echoed
 * as the response {@code ETag}, research.md R1), and every lesson's full content (FR-024). Only ever
 * returned by an endpoint {@code OwnershipGuard} has already approved the caller for.
 */
public record CourseFullView(
        UUID id,
        String title,
        String description,
        UUID categoryId,
        String level,
        String language,
        Integer priceCents,
        String currency,
        UUID instructorId,
        String status,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt,
        long version,
        List<LessonView> lessons) {

    public record LessonView(UUID id, String title, int position, int durationMinutes, String content) {
        static LessonView of(Lesson lesson) {
            return new LessonView(lesson.getId(), lesson.getTitle(), lesson.getPosition(),
                    lesson.getDurationMinutes(), lesson.getContent());
        }
    }

    public static CourseFullView of(Course course) {
        return new CourseFullView(
                course.getId(),
                course.getTitle(),
                course.getDescription(),
                course.getCategoryId(),
                course.getLevel() == null ? null : course.getLevel().name(),
                course.getLanguage(),
                course.getPriceCents(),
                course.getCurrency(),
                course.getInstructorId(),
                course.getStatus().name(),
                course.getPublishedAt(),
                course.getCreatedAt(),
                course.getUpdatedAt(),
                course.getVersion(),
                course.getLessons().stream().map(LessonView::of).toList());
    }
}

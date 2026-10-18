package com.example.learnhub.course.api;

import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.Lesson;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** FR-006: the public course view — no lesson content anywhere. */
public record CoursePublicView(
        UUID id,
        String title,
        String description,
        UUID categoryId,
        String level,
        String language,
        Integer priceCents,
        String currency,
        UUID instructorId,
        Instant publishedAt,
        List<LessonOutline> lessons) {

    public record LessonOutline(String title, int position, int durationMinutes) {
        static LessonOutline of(Lesson lesson) {
            return new LessonOutline(lesson.getTitle(), lesson.getPosition(), lesson.getDurationMinutes());
        }
    }

    public static CoursePublicView of(Course course) {
        return new CoursePublicView(
                course.getId(),
                course.getTitle(),
                course.getDescription(),
                course.getCategoryId(),
                course.getLevel() == null ? null : course.getLevel().name(),
                course.getLanguage(),
                course.getPriceCents(),
                course.getCurrency(),
                course.getInstructorId(),
                course.getPublishedAt(),
                course.getLessons().stream().map(LessonOutline::of).toList());
    }
}

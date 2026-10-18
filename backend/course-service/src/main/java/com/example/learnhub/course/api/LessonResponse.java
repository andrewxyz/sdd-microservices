package com.example.learnhub.course.api;

import com.example.learnhub.course.domain.Lesson;
import java.util.UUID;

/** A single lesson, full detail — only ever returned to a caller {@code OwnershipGuard} approved. */
public record LessonResponse(UUID id, String title, int position, int durationMinutes, String content) {

    public static LessonResponse of(Lesson lesson) {
        return new LessonResponse(lesson.getId(), lesson.getTitle(), lesson.getPosition(),
                lesson.getDurationMinutes(), lesson.getContent());
    }
}

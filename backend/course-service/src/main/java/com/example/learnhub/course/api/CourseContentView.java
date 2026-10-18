package com.example.learnhub.course.api;

import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.Lesson;
import java.util.List;
import java.util.UUID;

/**
 * The reading view returned by {@code GET /api/v1/courses/{id}/content} (specs/004-checkout-enrollment
 * research.md R5). Unlike 003's {@code CourseFullView} (the owner/admin editing view), this carries no
 * {@code status} or {@code version} — it is for an enrolled student, the owner, or an administrator to
 * read, not to edit.
 */
public record CourseContentView(UUID courseId, String title, List<LessonView> lessons) {

    public record LessonView(String title, int position, int durationMinutes, String content) {

        static LessonView of(Lesson lesson) {
            return new LessonView(lesson.getTitle(), lesson.getPosition(), lesson.getDurationMinutes(),
                    lesson.getContent());
        }
    }

    public static CourseContentView of(Course course, List<Lesson> lessons) {
        List<LessonView> views = lessons.stream().map(LessonView::of).toList();
        return new CourseContentView(course.getId(), course.getTitle(), views);
    }
}

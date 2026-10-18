package com.example.learnhub.course.api;

import com.example.learnhub.course.domain.Course;
import java.util.UUID;

/** FR-023, research.md R6: the service-to-service lookup response, regardless of course status. */
public record CourseSummaryView(UUID courseId, String status, Integer priceCents, String currency,
        UUID instructorId, long lessonCount) {

    public static CourseSummaryView of(Course course, long lessonCount) {
        return new CourseSummaryView(course.getId(), course.getStatus().name(), course.getPriceCents(),
                course.getCurrency(), course.getInstructorId(), lessonCount);
    }
}

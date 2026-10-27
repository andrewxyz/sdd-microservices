package com.example.learnhub.analytics.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/** One entry of course-service's internal list of an instructor's courses. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstructorCourseItem(UUID courseId, String status, String title) {
}

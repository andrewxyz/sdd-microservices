package com.example.learnhub.analytics.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/** The only part of course-service's internal lookup that analytics-service reads. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CourseOwner(UUID courseId, UUID instructorId) {
}

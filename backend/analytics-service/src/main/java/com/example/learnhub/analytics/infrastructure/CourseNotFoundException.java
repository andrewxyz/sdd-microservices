package com.example.learnhub.analytics.infrastructure;

import java.util.UUID;

/** course-service answered that there is no such course. */
public class CourseNotFoundException extends RuntimeException {

    public CourseNotFoundException(UUID courseId) {
        super("No such course: " + courseId);
    }
}

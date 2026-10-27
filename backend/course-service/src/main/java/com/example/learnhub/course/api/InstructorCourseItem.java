package com.example.learnhub.course.api;

import com.example.learnhub.course.domain.Course;
import java.util.UUID;

/**
 * One entry of the internal list of an instructor's courses (specs/009-instructor-dashboard/contracts/
 * course-service-internal-list.openapi.yaml): just enough to tell analytics-service which courses exist
 * and how to label them.
 */
public record InstructorCourseItem(UUID courseId, String status, String title) {

    public static InstructorCourseItem of(Course course) {
        return new InstructorCourseItem(course.getId(), course.getStatus().name(), course.getTitle());
    }
}

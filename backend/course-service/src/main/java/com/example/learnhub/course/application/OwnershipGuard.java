package com.example.learnhub.course.application;

import com.example.learnhub.course.domain.Course;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * FR-011: only a course's owning instructor or an administrator may edit it, manage its lessons,
 * publish it, archive it, or delete a draft of it. Gateway routing only establishes "this caller has a
 * valid token" (research.md R9); ownership is a record-level fact only this service can check.
 */
@Component
public class OwnershipGuard {

    private static final String ADMINISTRATOR = "ADMINISTRATOR";

    public void requireOwnerOrAdmin(Course course, UUID callerId, String callerRole) {
        if (ADMINISTRATOR.equals(callerRole)) {
            return;
        }
        if (course.getInstructorId().equals(callerId)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "You are neither this course's owner nor an administrator.");
    }
}

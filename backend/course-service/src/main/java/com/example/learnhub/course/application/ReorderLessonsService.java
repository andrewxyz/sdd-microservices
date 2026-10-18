package com.example.learnhub.course.application;

import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.Lesson;
import com.example.learnhub.course.infrastructure.ConcurrencyControl;
import com.example.learnhub.course.infrastructure.CourseRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * FR-010: reorders a course's lessons. Refused (422) unless the submitted list names exactly the
 * course's current lessons, each exactly once (spec.md Edge Cases) — no partial reorder is ever applied.
 */
@Service
public class ReorderLessonsService {

    private final CourseRepository courses;
    private final OwnershipGuard ownershipGuard;

    public ReorderLessonsService(CourseRepository courses, OwnershipGuard ownershipGuard) {
        this.courses = courses;
        this.ownershipGuard = ownershipGuard;
    }

    @Transactional
    public void reorder(UUID courseId, List<UUID> lessonIdsInOrder, String ifMatch, UUID callerId,
            String callerRole) {
        Course course = courses.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such course."));
        ownershipGuard.requireOwnerOrAdmin(course, callerId, callerRole);
        ConcurrencyControl.requireMatch(ifMatch, course.getVersion());

        Map<UUID, Lesson> byId = course.getLessons().stream()
                .collect(Collectors.toMap(Lesson::getId, lesson -> lesson));
        Set<UUID> submitted = Set.copyOf(lessonIdsInOrder);
        if (submitted.size() != lessonIdsInOrder.size() || !submitted.equals(byId.keySet())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The list must name exactly this course's current lessons, each exactly once.");
        }

        for (int i = 0; i < lessonIdsInOrder.size(); i++) {
            byId.get(lessonIdsInOrder.get(i)).setPosition(i);
        }
        course.touch(Instant.now());
        courses.save(course);
    }
}

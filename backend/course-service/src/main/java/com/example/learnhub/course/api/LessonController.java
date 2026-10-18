package com.example.learnhub.course.api;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.course.application.LessonService;
import com.example.learnhub.course.application.ReorderLessonsService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/courses/{courseId}/lessons}: add, edit, remove and reorder a course's lessons
 * (FR-010). Owner/administrator only, enforced inside {@link LessonService}/{@link ReorderLessonsService}.
 */
@RestController
@RequestMapping("/api/v1/courses/{courseId}/lessons")
public class LessonController {

    private final LessonService lessonService;
    private final ReorderLessonsService reorderLessonsService;

    public LessonController(LessonService lessonService, ReorderLessonsService reorderLessonsService) {
        this.lessonService = lessonService;
        this.reorderLessonsService = reorderLessonsService;
    }

    public record ReorderRequest(List<UUID> lessonIdsInOrder) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LessonResponse add(@PathVariable UUID courseId, @RequestBody LessonService.Request request,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader(IdentityHeaders.USER_ID) UUID callerId,
            @RequestHeader(IdentityHeaders.USER_ROLE) String callerRole) {
        return lessonService.add(courseId, request, ifMatch, callerId, callerRole);
    }

    @PutMapping("/{lessonId}")
    public LessonResponse edit(@PathVariable UUID courseId, @PathVariable UUID lessonId,
            @RequestBody LessonService.Request request,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader(IdentityHeaders.USER_ID) UUID callerId,
            @RequestHeader(IdentityHeaders.USER_ROLE) String callerRole) {
        return lessonService.edit(courseId, lessonId, request, ifMatch, callerId, callerRole);
    }

    @DeleteMapping("/{lessonId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID courseId, @PathVariable UUID lessonId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader(IdentityHeaders.USER_ID) UUID callerId,
            @RequestHeader(IdentityHeaders.USER_ROLE) String callerRole) {
        lessonService.remove(courseId, lessonId, ifMatch, callerId, callerRole);
    }

    @PatchMapping("/reorder")
    public void reorder(@PathVariable UUID courseId, @RequestBody ReorderRequest request,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader(IdentityHeaders.USER_ID) UUID callerId,
            @RequestHeader(IdentityHeaders.USER_ROLE) String callerRole) {
        reorderLessonsService.reorder(courseId, request.lessonIdsInOrder(), ifMatch, callerId, callerRole);
    }
}

package com.example.learnhub.course.api;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.course.application.ArchiveCourseService;
import com.example.learnhub.course.application.CatalogQueryService;
import com.example.learnhub.course.application.CourseDetailService;
import com.example.learnhub.course.application.CreateCourseService;
import com.example.learnhub.course.application.DeleteCourseService;
import com.example.learnhub.course.application.EditCourseService;
import com.example.learnhub.course.application.PublishCourseService;
import com.example.learnhub.course.domain.Level;
import com.example.learnhub.course.infrastructure.ConcurrencyControl;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/courses}: the public list and detail (FR-001, FR-006) are unauthenticated; create,
 * edit and delete (FR-008 to FR-013) require a valid token, enforced by the gateway's {@code
 * courses-manage} catch-all, with ownership/role nuance enforced here (research.md R9).
 */
@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

    private final CatalogQueryService catalogQueryService;
    private final CourseDetailService courseDetailService;
    private final CreateCourseService createCourseService;
    private final EditCourseService editCourseService;
    private final DeleteCourseService deleteCourseService;
    private final PublishCourseService publishCourseService;
    private final ArchiveCourseService archiveCourseService;

    public CourseController(CatalogQueryService catalogQueryService, CourseDetailService courseDetailService,
            CreateCourseService createCourseService, EditCourseService editCourseService,
            DeleteCourseService deleteCourseService, PublishCourseService publishCourseService,
            ArchiveCourseService archiveCourseService) {
        this.catalogQueryService = catalogQueryService;
        this.courseDetailService = courseDetailService;
        this.createCourseService = createCourseService;
        this.editCourseService = editCourseService;
        this.deleteCourseService = deleteCourseService;
        this.publishCourseService = publishCourseService;
        this.archiveCourseService = archiveCourseService;
    }

    public record CatalogPage(Iterable<CoursePublicView> items, int page, int size, long totalItems) {
    }

    @GetMapping
    public CatalogPage list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) UUID category,
            @RequestParam(required = false) Level level,
            @RequestParam(required = false) Boolean free,
            @RequestParam(required = false) String q) {
        Page<CoursePublicView> result = catalogQueryService.search(page, size, sort, category, level, free, q);
        return new CatalogPage(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @GetMapping("/{courseId}")
    public CoursePublicView detail(@PathVariable UUID courseId) {
        return courseDetailService.viewPublic(courseId);
    }

    @PostMapping
    public ResponseEntity<CourseFullView> create(@RequestBody CreateCourseService.Request request,
            @RequestHeader(IdentityHeaders.USER_ID) UUID callerId,
            @RequestHeader(IdentityHeaders.USER_ROLE) String callerRole) {
        CourseFullView view = createCourseService.create(request, callerId, callerRole);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, ConcurrencyControl.etagOf(view.version()))
                .body(view);
    }

    @PutMapping("/{courseId}")
    public ResponseEntity<CourseFullView> edit(@PathVariable UUID courseId,
            @RequestBody EditCourseService.Request request,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader(IdentityHeaders.USER_ID) UUID callerId,
            @RequestHeader(IdentityHeaders.USER_ROLE) String callerRole) {
        CourseFullView view = editCourseService.edit(courseId, request, ifMatch, callerId, callerRole);
        return withEtag(view);
    }

    @DeleteMapping("/{courseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID courseId,
            @RequestHeader(IdentityHeaders.USER_ID) UUID callerId,
            @RequestHeader(IdentityHeaders.USER_ROLE) String callerRole) {
        deleteCourseService.delete(courseId, callerId, callerRole);
    }

    @PatchMapping("/{courseId}/publish")
    public ResponseEntity<CourseFullView> publish(@PathVariable UUID courseId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader(IdentityHeaders.USER_ID) UUID callerId,
            @RequestHeader(IdentityHeaders.USER_ROLE) String callerRole) {
        return withEtag(publishCourseService.publish(courseId, ifMatch, callerId, callerRole));
    }

    @PatchMapping("/{courseId}/archive")
    public ResponseEntity<CourseFullView> archive(@PathVariable UUID courseId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader(IdentityHeaders.USER_ID) UUID callerId,
            @RequestHeader(IdentityHeaders.USER_ROLE) String callerRole) {
        return withEtag(archiveCourseService.archive(courseId, ifMatch, callerId, callerRole));
    }

    private static ResponseEntity<CourseFullView> withEtag(CourseFullView view) {
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ConcurrencyControl.etagOf(view.version()))
                .body(view);
    }
}

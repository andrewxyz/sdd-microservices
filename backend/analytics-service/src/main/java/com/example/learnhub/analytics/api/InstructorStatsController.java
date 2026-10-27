package com.example.learnhub.analytics.api;

import com.example.learnhub.analytics.api.StatsViews.CourseDetail;
import com.example.learnhub.analytics.api.StatsViews.CourseList;
import com.example.learnhub.analytics.api.StatsViews.Summary;
import com.example.learnhub.analytics.application.StatsQueryService;
import com.example.learnhub.analytics.domain.InvalidParameterException;
import com.example.learnhub.analytics.domain.StatsMath;
import com.example.learnhub.common.identity.IdentityHeaders;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboard endpoints (specs/009 contracts/analytics-service.openapi.yaml). The gateway restricts the
 * path to instructors and administrators; every method re-checks the role itself before doing anything else.
 */
@RestController
@RequestMapping("/api/v1/instructor/stats")
public class InstructorStatsController {

    private static final int MAX_PAGE_SIZE = 200;

    private final StatsQueryService queries;

    public InstructorStatsController(StatsQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/summary")
    public Summary summary(@RequestHeader(value = IdentityHeaders.USER_ID, required = false) String userId,
            @RequestHeader(value = IdentityHeaders.USER_ROLE, required = false) String role) {
        return queries.summary(CallerContext.require(userId, role));
    }

    @GetMapping("/courses")
    public CourseList courses(@RequestHeader(value = IdentityHeaders.USER_ID, required = false) String userId,
            @RequestHeader(value = IdentityHeaders.USER_ROLE, required = false) String role,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        CallerContext caller = CallerContext.require(userId, role);
        if (page < 0) {
            throw new InvalidParameterException("page", "page must be 0 or more");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidParameterException("size", "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return queries.courses(caller, page, size);
    }

    @GetMapping("/courses/{courseId}")
    public CourseDetail course(@RequestHeader(value = IdentityHeaders.USER_ID, required = false) String userId,
            @RequestHeader(value = IdentityHeaders.USER_ROLE, required = false) String role,
            @PathVariable String courseId, @RequestParam(required = false) String days) {
        CallerContext caller = CallerContext.require(userId, role);
        return queries.detail(caller, parseCourseId(courseId), StatsMath.validateDays(days));
    }

    private static UUID parseCourseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterException("courseId", "courseId must be a UUID");
        }
    }
}

package com.example.learnhub.analytics.application;

import com.example.learnhub.analytics.api.CallerContext;
import com.example.learnhub.analytics.api.StatsViews.CourseDetail;
import com.example.learnhub.analytics.api.StatsViews.CourseList;
import com.example.learnhub.analytics.api.StatsViews.CourseStats;
import com.example.learnhub.analytics.api.StatsViews.Funnel;
import com.example.learnhub.analytics.api.StatsViews.Summary;
import com.example.learnhub.analytics.api.StatsViews.Totals;
import com.example.learnhub.analytics.domain.DailyCount;
import com.example.learnhub.analytics.domain.StatsMath;
import com.example.learnhub.analytics.infrastructure.CourseNotFoundException;
import com.example.learnhub.analytics.infrastructure.CourseOwner;
import com.example.learnhub.analytics.infrastructure.CourseOwnershipClient;
import com.example.learnhub.analytics.infrastructure.InstructorCourseItem;
import com.example.learnhub.analytics.infrastructure.StatsRepository;
import com.example.learnhub.analytics.infrastructure.StatsRepository.CourseTotals;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Answers the dashboard queries (spec FR-001..FR-009, FR-016). Course ownership is asked of course-service
 * at query time (research R1) and a failure to confirm it propagates as
 * {@code OwnershipUnavailableException}, so no figure is ever produced for an unconfirmed owner.
 */
@Service
public class StatsQueryService {

    /** course-service returns at most this many courses; reaching it means the list may be cut off. */
    static final int OWNED_COURSE_CAP = 200;

    private final StatsRepository stats;
    private final CourseOwnershipClient ownership;
    private final Clock clock;

    public StatsQueryService(StatsRepository stats, CourseOwnershipClient ownership, Clock clock) {
        this.stats = stats;
        this.ownership = ownership;
        this.clock = clock;
    }

    public Summary summary(CallerContext caller) {
        if (caller.isAdministrator()) {
            return new Summary("PLATFORM", totals(stats.platformTotals()), false, Instant.now(clock));
        }
        List<InstructorCourseItem> owned = ownership.listByInstructor(caller.userId());
        StatsRepository.Totals totals = stats.totalsForCourses(ids(owned));
        return new Summary("OWN", totals(totals), truncated(owned), Instant.now(clock));
    }

    /** Instructors: their courses that have activity, in one page. Administrators: every course, paged. */
    public CourseList courses(CallerContext caller, int page, int size) {
        if (caller.isAdministrator()) {
            List<CourseStats> items =
                    stats.platformPerCourse(page, size).stream().map(StatsQueryService::view).toList();
            return new CourseList(items, page, size, stats.platformCourseCount(), false, Instant.now(clock));
        }
        List<InstructorCourseItem> owned = ownership.listByInstructor(caller.userId());
        List<CourseStats> items = stats.perCourse(ids(owned)).stream().map(StatsQueryService::view).toList();
        return new CourseList(items, 0, items.size(), items.size(), truncated(owned), Instant.now(clock));
    }

    /**
     * One course. A non-owner who is not an administrator gets exactly the same not-found answer as for a
     * course that does not exist (spec FR-008), so the two cannot be told apart.
     */
    public CourseDetail detail(CallerContext caller, UUID courseId, int days) {
        authorise(caller, courseId);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        StatsRepository.Totals totals = stats.totalsForCourse(courseId);
        List<DailyCount> series = StatsMath.zeroFill(today, days,
                stats.dailyEnrollments(courseId, today.minusDays(days - 1L), today));
        return new CourseDetail(courseId, days, totals(totals),
                new Funnel(totals.enrollments(), totals.completions(), totals.certificates()), series,
                Instant.now(clock));
    }

    private void authorise(CallerContext caller, UUID courseId) {
        CourseOwner owner;
        try {
            owner = ownership.detail(courseId);
        } catch (CourseNotFoundException e) {
            throw notFound();
        }
        if (!caller.isAdministrator() && !caller.userId().equals(owner.instructorId())) {
            throw notFound();
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such course.");
    }

    private static List<UUID> ids(List<InstructorCourseItem> owned) {
        return owned.stream().map(InstructorCourseItem::courseId).toList();
    }

    private static boolean truncated(List<InstructorCourseItem> owned) {
        return owned.size() >= OWNED_COURSE_CAP;
    }

    private static Totals totals(StatsRepository.Totals t) {
        return new Totals(t.enrollments(), t.revenueCents(), t.completions(), t.certificates());
    }

    private static CourseStats view(CourseTotals row) {
        return new CourseStats(row.courseId(), totals(row.totals()));
    }
}

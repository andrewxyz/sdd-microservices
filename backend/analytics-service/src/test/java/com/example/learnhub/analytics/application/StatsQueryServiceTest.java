package com.example.learnhub.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.learnhub.analytics.api.CallerContext;
import com.example.learnhub.analytics.api.StatsViews.CourseDetail;
import com.example.learnhub.analytics.api.StatsViews.CourseList;
import com.example.learnhub.analytics.api.StatsViews.Summary;
import com.example.learnhub.analytics.domain.DailyCount;
import com.example.learnhub.analytics.infrastructure.CourseNotFoundException;
import com.example.learnhub.analytics.infrastructure.CourseOwner;
import com.example.learnhub.analytics.infrastructure.CourseOwnershipClient;
import com.example.learnhub.analytics.infrastructure.InstructorCourseItem;
import com.example.learnhub.analytics.infrastructure.OwnershipUnavailableException;
import com.example.learnhub.analytics.infrastructure.StatsRepository;
import com.example.learnhub.analytics.infrastructure.StatsRepository.CourseTotals;
import com.example.learnhub.analytics.infrastructure.StatsRepository.Totals;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Spec FR-001..FR-009, FR-016: who sees what, with fake storage and a fake course-service. */
class StatsQueryServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC);

    private StatsRepository stats;
    private CourseOwnershipClient ownership;
    private StatsQueryService service;

    private final UUID instructorId = UUID.randomUUID();
    private final CallerContext instructor = new CallerContext(instructorId, CallerContext.Role.INSTRUCTOR);
    private final CallerContext admin = new CallerContext(UUID.randomUUID(), CallerContext.Role.ADMINISTRATOR);

    @BeforeEach
    void setUp() {
        stats = mock(StatsRepository.class);
        ownership = mock(CourseOwnershipClient.class);
        service = new StatsQueryService(stats, ownership, CLOCK);
    }

    private static InstructorCourseItem item(UUID id) {
        return new InstructorCourseItem(id, "PUBLISHED", "Course");
    }

    @Test
    void anInstructorSummaryCoversOnlyTheirOwnCourses() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(ownership.listByInstructor(instructorId)).thenReturn(List.of(item(a), item(b)));
        when(stats.totalsForCourses(List.of(a, b))).thenReturn(new Totals(5, 9995, 2, 1));

        Summary summary = service.summary(instructor);

        assertThat(summary.scope()).isEqualTo("OWN");
        assertThat(summary.totals().enrollments()).isEqualTo(5);
        assertThat(summary.totals().revenueCents()).isEqualTo(9995);
        assertThat(summary.totals().completions()).isEqualTo(2);
        assertThat(summary.totals().certificates()).isEqualTo(1);
        assertThat(summary.truncated()).isFalse();
        assertThat(summary.generatedAt()).isEqualTo(Instant.parse("2026-03-15T12:00:00Z"));
        verify(stats, never()).platformTotals();
    }

    @Test
    void anInstructorWhoOwnsNoCoursesGetsZeroTotals() {
        when(ownership.listByInstructor(instructorId)).thenReturn(List.of());
        when(stats.totalsForCourses(List.of())).thenReturn(Totals.ZERO);

        Summary summary = service.summary(instructor);

        assertThat(summary.totals().enrollments()).isZero();
        assertThat(summary.totals().revenueCents()).isZero();
    }

    @Test
    void anAdministratorSummaryIsPlatformWideWithoutAnOwnershipLookup() {
        when(stats.platformTotals()).thenReturn(new Totals(40, 100000, 10, 8));

        Summary summary = service.summary(admin);

        assertThat(summary.scope()).isEqualTo("PLATFORM");
        assertThat(summary.totals().enrollments()).isEqualTo(40);
        assertThat(summary.truncated()).isFalse();
        verifyNoInteractions(ownership);
    }

    @Test
    void anInstructorListHoldsOnlyOwnedCoursesThatHaveActivity() {
        UUID active = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();
        when(ownership.listByInstructor(instructorId)).thenReturn(List.of(item(active), item(quiet)));
        when(stats.perCourse(List.of(active, quiet)))
                .thenReturn(List.of(new CourseTotals(active, new Totals(3, 3000, 1, 1))));

        CourseList list = service.courses(instructor, 0, 50);

        assertThat(list.items()).hasSize(1);
        assertThat(list.items().get(0).courseId()).isEqualTo(active);
        assertThat(list.items().get(0).totals().revenueCents()).isEqualTo(3000);
        assertThat(list.totalItems()).isEqualTo(1);
    }

    @Test
    void anAdministratorListIsPagedAndNeedsNoOwnershipLookup() {
        UUID top = UUID.randomUUID();
        when(stats.platformPerCourse(2, 25)).thenReturn(List.of(new CourseTotals(top, new Totals(9, 9999, 4, 3))));
        when(stats.platformCourseCount()).thenReturn(61L);

        CourseList list = service.courses(admin, 2, 25);

        assertThat(list.page()).isEqualTo(2);
        assertThat(list.size()).isEqualTo(25);
        assertThat(list.totalItems()).isEqualTo(61);
        assertThat(list.items()).extracting(c -> c.courseId()).containsExactly(top);
        verifyNoInteractions(ownership);
    }

    @Test
    void reachingTheTwoHundredCourseLimitIsFlaggedAsTruncated() {
        List<InstructorCourseItem> owned =
                IntStream.range(0, 200).mapToObj(i -> item(UUID.randomUUID())).toList();
        when(ownership.listByInstructor(instructorId)).thenReturn(owned);
        when(stats.totalsForCourses(any())).thenReturn(Totals.ZERO);
        when(stats.perCourse(any())).thenReturn(List.of());

        assertThat(service.summary(instructor).truncated()).isTrue();
        assertThat(service.courses(instructor, 0, 50).truncated()).isTrue();
    }

    @Test
    void fewerThanTwoHundredCoursesIsNotTruncated() {
        List<InstructorCourseItem> owned =
                IntStream.range(0, 199).mapToObj(i -> item(UUID.randomUUID())).toList();
        when(ownership.listByInstructor(instructorId)).thenReturn(owned);
        when(stats.totalsForCourses(any())).thenReturn(Totals.ZERO);

        assertThat(service.summary(instructor).truncated()).isFalse();
    }

    @Test
    void anOwnershipFailureProducesNoFigures() {
        when(ownership.listByInstructor(instructorId)).thenThrow(new OwnershipUnavailableException("x", null));

        assertThatThrownBy(() -> service.summary(instructor)).isInstanceOf(OwnershipUnavailableException.class);
        assertThatThrownBy(() -> service.courses(instructor, 0, 50)).isInstanceOf(OwnershipUnavailableException.class);
        verifyNoInteractions(stats);
    }

    // ---- detail (spec 009 tasks T039) --------------------------------------------------------------

    @Test
    void theOwnerSeesTheSeriesZeroFilledFromTheOldestDayToTodayUtc() {
        UUID courseId = UUID.randomUUID();
        LocalDate today = LocalDate.of(2026, 3, 15);
        when(ownership.detail(courseId)).thenReturn(new CourseOwner(courseId, instructorId));
        when(stats.totalsForCourse(courseId)).thenReturn(new Totals(6, 6000, 3, 2));
        when(stats.dailyEnrollments(courseId, today.minusDays(6), today))
                .thenReturn(Map.of(today, 4L, today.minusDays(3), 2L));

        CourseDetail detail = service.detail(instructor, courseId, 7);

        assertThat(detail.days()).isEqualTo(7);
        assertThat(detail.series()).hasSize(7);
        assertThat(detail.series().get(0).day()).isEqualTo(today.minusDays(6));
        assertThat(detail.series().get(6).day()).isEqualTo(today);
        assertThat(detail.series()).extracting(DailyCount::enrollments).containsExactly(0L, 0L, 0L, 2L, 0L, 0L, 4L);
        assertThat(detail.funnel().enrolled()).isEqualTo(6);
        assertThat(detail.funnel().completed()).isEqualTo(3);
        assertThat(detail.funnel().certificateIssued()).isEqualTo(2);
        assertThat(detail.totals().revenueCents()).isEqualTo(6000);
    }

    @Test
    void aNonOwnerGetsTheSameNotFoundAsForAnUnknownCourse() {
        UUID someonesCourse = UUID.randomUUID();
        UUID unknownCourse = UUID.randomUUID();
        when(ownership.detail(someonesCourse)).thenReturn(new CourseOwner(someonesCourse, UUID.randomUUID()));
        when(ownership.detail(unknownCourse)).thenThrow(new CourseNotFoundException(unknownCourse));

        ResponseStatusException foreign = catchStatus(() -> service.detail(instructor, someonesCourse, 30));
        ResponseStatusException unknown = catchStatus(() -> service.detail(instructor, unknownCourse, 30));

        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreign.getReason()).isEqualTo(unknown.getReason());
        verifyNoInteractions(stats);
    }

    @Test
    void anAdministratorCanOpenAnyExistingCourse() {
        UUID courseId = UUID.randomUUID();
        when(ownership.detail(courseId)).thenReturn(new CourseOwner(courseId, UUID.randomUUID()));
        when(stats.totalsForCourse(courseId)).thenReturn(Totals.ZERO);
        when(stats.dailyEnrollments(any(), any(), any())).thenReturn(Map.of());

        assertThat(service.detail(admin, courseId, 30).series()).hasSize(30);
    }

    private static ResponseStatusException catchStatus(Runnable action) {
        try {
            action.run();
        } catch (ResponseStatusException e) {
            return e;
        }
        throw new AssertionError("expected a ResponseStatusException");
    }
}

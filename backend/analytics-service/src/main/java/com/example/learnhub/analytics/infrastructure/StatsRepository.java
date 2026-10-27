package com.example.learnhub.analytics.infrastructure;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Counters per course and UTC day. Writes are one atomic {@code INSERT ... ON CONFLICT DO UPDATE} that
 * adds deltas, so concurrent and out-of-order events always sum correctly (research R4).
 */
@Repository
public class StatsRepository {

    /** Totals of the four counters. */
    public record Totals(long enrollments, long revenueCents, long completions, long certificates) {
        public static final Totals ZERO = new Totals(0, 0, 0, 0);
    }

    /** Totals of one course. */
    public record CourseTotals(UUID courseId, Totals totals) {
    }

    private static final String UPSERT = """
            INSERT INTO course_daily_stats (course_id, day, enrollments, revenue_cents, completions, certificates)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (course_id, day) DO UPDATE SET
                enrollments   = course_daily_stats.enrollments   + EXCLUDED.enrollments,
                revenue_cents = course_daily_stats.revenue_cents + EXCLUDED.revenue_cents,
                completions   = course_daily_stats.completions   + EXCLUDED.completions,
                certificates  = course_daily_stats.certificates  + EXCLUDED.certificates
            """;

    private static final String SUMS = """
            SELECT COALESCE(SUM(enrollments), 0) AS enrollments, COALESCE(SUM(revenue_cents), 0) AS revenue_cents,
                   COALESCE(SUM(completions), 0) AS completions, COALESCE(SUM(certificates), 0) AS certificates
            FROM course_daily_stats
            """;

    private final JdbcTemplate jdbc;

    public StatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds the deltas to the course's counters for the UTC day. */
    public void add(UUID courseId, LocalDate day, long enrollments, long revenueCents, long completions,
            long certificates) {
        jdbc.update(UPSERT, courseId, Date.valueOf(day), enrollments, revenueCents, completions, certificates);
    }

    public Totals platformTotals() {
        return jdbc.queryForObject(SUMS, (rs, i) -> totals(rs));
    }

    public Totals totalsForCourses(Collection<UUID> courseIds) {
        if (courseIds.isEmpty()) {
            return Totals.ZERO;
        }
        String in = String.join(",", courseIds.stream().map(id -> "?").toList());
        return jdbc.queryForObject(SUMS + " WHERE course_id IN (" + in + ")", (rs, i) -> totals(rs),
                courseIds.toArray());
    }

    public Totals totalsForCourse(UUID courseId) {
        return jdbc.queryForObject(SUMS + " WHERE course_id = ?", (rs, i) -> totals(rs), courseId);
    }

    /** Per-course totals for the given courses, only those with any activity. */
    public List<CourseTotals> perCourse(Collection<UUID> courseIds) {
        if (courseIds.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", courseIds.stream().map(id -> "?").toList());
        return jdbc.query(perCourseSql(" WHERE course_id IN (" + in + ")", ""), (rs, i) -> courseTotals(rs),
                courseIds.toArray());
    }

    /** Every course with activity, highest revenue first, one page. */
    public List<CourseTotals> platformPerCourse(int page, int size) {
        return jdbc.query(perCourseSql("", " LIMIT ? OFFSET ?"), (rs, i) -> courseTotals(rs), size,
                (long) page * size);
    }

    public long platformCourseCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(DISTINCT course_id) FROM course_daily_stats", Long.class);
        return count == null ? 0 : count;
    }

    /** Enrollments per UTC day for one course from {@code from} to {@code to} inclusive; days without rows are absent. */
    public Map<LocalDate, Long> dailyEnrollments(UUID courseId, LocalDate from, LocalDate to) {
        Map<LocalDate, Long> counts = new HashMap<>();
        jdbc.query("SELECT day, enrollments FROM course_daily_stats WHERE course_id = ? AND day BETWEEN ? AND ?",
                rs -> {
                    counts.put(rs.getDate("day").toLocalDate(), rs.getLong("enrollments"));
                }, courseId, Date.valueOf(from), Date.valueOf(to));
        return counts;
    }

    private static String perCourseSql(String where, String paging) {
        return """
                SELECT course_id, SUM(enrollments) AS enrollments, SUM(revenue_cents) AS revenue_cents,
                       SUM(completions) AS completions, SUM(certificates) AS certificates
                FROM course_daily_stats
                """ + where + """
                 GROUP BY course_id
                 ORDER BY SUM(revenue_cents) DESC, SUM(enrollments) DESC, course_id
                """ + paging;
    }

    private static Totals totals(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Totals(rs.getLong("enrollments"), rs.getLong("revenue_cents"), rs.getLong("completions"),
                rs.getLong("certificates"));
    }

    private static CourseTotals courseTotals(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CourseTotals(rs.getObject("course_id", UUID.class), totals(rs));
    }
}

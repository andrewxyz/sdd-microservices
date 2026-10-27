package com.example.learnhub.analytics.api;

import com.example.learnhub.analytics.domain.DailyCount;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The response shapes of {@code /api/v1/instructor/stats/**} (specs/009 contracts/analytics-service.openapi.yaml). */
public final class StatsViews {

    private StatsViews() {
    }

    public record Totals(long enrollments, long revenueCents, long completions, long certificates) {
    }

    public record Summary(String scope, Totals totals, boolean truncated, Instant generatedAt) {
    }

    public record CourseStats(UUID courseId, Totals totals) {
    }

    public record CourseList(List<CourseStats> items, int page, int size, long totalItems, boolean truncated,
            Instant generatedAt) {
    }

    public record Funnel(long enrolled, long completed, long certificateIssued) {
    }

    public record CourseDetail(UUID courseId, int days, Totals totals, Funnel funnel, List<DailyCount> series,
            Instant generatedAt) {
    }
}

package com.example.learnhub.analytics.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The pure arithmetic behind the dashboard (spec FR-003, FR-005, FR-006), free of Spring and storage. */
public final class StatsMath {

    public static final int DEFAULT_DAYS = 30;

    private StatsMath() {
    }

    /** Completions divided by enrollments; 0 with no enrollments, and never above 1 (100%). */
    public static double completionRate(long completions, long enrollments) {
        if (enrollments <= 0) {
            return 0;
        }
        return Math.min(1.0, (double) completions / enrollments);
    }

    /**
     * Exactly {@code days} entries, oldest first, ending {@code today}; days missing from {@code counts}
     * are zero and counts outside the window are ignored.
     */
    public static List<DailyCount> zeroFill(LocalDate today, int days, Map<LocalDate, Long> counts) {
        List<DailyCount> series = new ArrayList<>(days);
        for (int offset = days - 1; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            series.add(new DailyCount(day, counts.getOrDefault(day, 0L)));
        }
        return series;
    }

    /** Null means "not given" and gives the default of 30; otherwise only 7, 30 and 90 are accepted. */
    public static int validateDays(String raw) {
        if (raw == null) {
            return DEFAULT_DAYS;
        }
        return switch (raw) {
            case "7" -> 7;
            case "30" -> 30;
            case "90" -> 90;
            default -> throw new InvalidDaysException("days must be 7, 30 or 90");
        };
    }
}

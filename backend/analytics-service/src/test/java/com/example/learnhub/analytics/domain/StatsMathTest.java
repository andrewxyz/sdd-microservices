package com.example.learnhub.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Spec FR-003, FR-005, FR-006: the pure arithmetic behind the dashboard, no Spring or database. */
class StatsMathTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    @Test
    void completionRateIsCompletionsOverEnrollments() {
        assertThat(StatsMath.completionRate(1, 4)).isEqualTo(0.25);
        assertThat(StatsMath.completionRate(3, 3)).isEqualTo(1.0);
    }

    @Test
    void completionRateIsZeroWhenThereAreNoEnrollments() {
        assertThat(StatsMath.completionRate(0, 0)).isZero();
        assertThat(StatsMath.completionRate(5, 0)).isZero();
    }

    @Test
    void completionRateNeverExceedsOneHundredPercent() {
        assertThat(StatsMath.completionRate(7, 4)).isEqualTo(1.0);
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 30, 90})
    void zeroFillReturnsExactlyDaysEntriesOldestFirstEndingToday(int days) {
        List<DailyCount> series = StatsMath.zeroFill(TODAY, days, Map.of());

        assertThat(series).hasSize(days);
        assertThat(series.get(days - 1).day()).isEqualTo(TODAY);
        assertThat(series.get(0).day()).isEqualTo(TODAY.minusDays(days - 1L));
        assertThat(series).allSatisfy(entry -> assertThat(entry.enrollments()).isZero());
    }

    @Test
    void zeroFillKeepsKnownDaysAndFillsGapsWithZero() {
        Map<LocalDate, Long> counts = Map.of(TODAY, 3L, TODAY.minusDays(2), 5L, TODAY.minusDays(40), 99L);

        List<DailyCount> series = StatsMath.zeroFill(TODAY, 7, counts);

        assertThat(series).extracting(DailyCount::enrollments).containsExactly(0L, 0L, 0L, 0L, 5L, 0L, 3L);
    }

    @Test
    void daysDefaultsToThirtyWhenAbsent() {
        assertThat(StatsMath.validateDays(null)).isEqualTo(30);
    }

    @ParameterizedTest
    @ValueSource(strings = {"7", "30", "90"})
    void daysAcceptsOnlySevenThirtyAndNinety(String raw) {
        assertThat(StatsMath.validateDays(raw)).isEqualTo(Integer.parseInt(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "45", "-7", "91", "abc", "", "7.5", " 30"})
    void daysRejectsEverythingElse(String raw) {
        assertThatThrownBy(() -> StatsMath.validateDays(raw)).isInstanceOf(InvalidDaysException.class);
    }
}

package com.example.learnhub.analytics.domain;

import java.time.LocalDate;

/** Enrollments counted on one UTC day. */
public record DailyCount(LocalDate day, long enrollments) {
}

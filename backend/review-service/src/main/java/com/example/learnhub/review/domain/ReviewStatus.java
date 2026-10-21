package com.example.learnhub.review.domain;

/**
 * A review's lifecycle state (data-model.md). {@code DELETED} is a soft delete: the audit row is
 * retained, but the review is excluded from public listings and the aggregate rating (FR-005, FR-009).
 */
public enum ReviewStatus {
    ACTIVE,
    DELETED
}

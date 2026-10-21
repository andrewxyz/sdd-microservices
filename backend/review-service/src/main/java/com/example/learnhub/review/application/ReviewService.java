package com.example.learnhub.review.application;

import com.example.learnhub.review.domain.Review;
import com.example.learnhub.review.domain.ReviewStatus;
import com.example.learnhub.review.infrastructure.EnrollmentLookupClient;
import com.example.learnhub.review.infrastructure.EnrollmentLookupResponse;
import com.example.learnhub.review.infrastructure.ReviewRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates review submission, editing and deletion (FR-001 to FR-006; data-model.md).
 */
@Service
public class ReviewService {

    private final ReviewRepository reviews;
    private final EnrollmentLookupClient enrollmentLookupClient;
    private final ReviewValidator validator;

    public ReviewService(ReviewRepository reviews, EnrollmentLookupClient enrollmentLookupClient,
            ReviewValidator validator) {
        this.reviews = reviews;
        this.enrollmentLookupClient = enrollmentLookupClient;
        this.validator = validator;
    }

    /** The result of a create-or-update submission: whether a new row was created (for the 201/200 split). */
    public record SubmitResult(Review review, boolean created) {
    }

    /**
     * Creates or updates the caller's review for a course (FR-001, FR-002, FR-003). Rejects the write with
     * a 403 if the caller is not enrolled (research.md R2), and never silently approves the write when
     * enrollment status cannot be confirmed — {@link EnrollmentLookupClient}'s fallback throws instead of
     * returning a default value.
     */
    @Transactional
    public SubmitResult submit(UUID userId, UUID courseId, int rating, String comment) {
        EnrollmentLookupResponse enrollment = enrollmentLookupClient.lookup(courseId, userId);
        if (!enrollment.enrolled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You must be enrolled in this course to review it.");
        }

        validator.validate(rating, comment);

        Instant now = Instant.now();
        Optional<Review> existing = reviews.findByUserIdAndCourseId(userId, courseId);
        if (existing.isPresent()) {
            Review review = existing.get();
            review.applyEdit((short) rating, comment, now);
            return new SubmitResult(review, false);
        }

        Review review = new Review(UUID.randomUUID(), userId, courseId, (short) rating, comment,
                ReviewStatus.ACTIVE, now, now);
        reviews.save(review);
        return new SubmitResult(review, true);
    }

    /** Edits the caller's own review (FR-004); 404 if missing or not owned by the caller (FR-006). */
    @Transactional
    public Review edit(UUID reviewId, UUID callerId, int rating, String comment) {
        Review review = findOwned(reviewId, callerId);
        validator.validate(rating, comment);
        review.applyEdit((short) rating, comment, Instant.now());
        return review;
    }

    /** Soft-deletes the caller's own review (FR-005); 404 if missing or not owned by the caller (FR-006). */
    @Transactional
    public void delete(UUID reviewId, UUID callerId) {
        Review review = findOwned(reviewId, callerId);
        review.markDeleted(Instant.now());
    }

    private Review findOwned(UUID reviewId, UUID callerId) {
        Review review = reviews.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such review."));
        // Identical 404 for "doesn't exist" and "belongs to someone else" (FR-006) — never 403, which
        // would confirm the review's existence to a caller who doesn't own it.
        if (!review.getUserId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such review.");
        }
        return review;
    }
}

package com.example.learnhub.review.api;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.review.application.ReviewService;
import com.example.learnhub.review.application.ReviewService.SubmitResult;
import com.example.learnhub.review.domain.Review;
import com.example.learnhub.review.domain.ReviewStatus;
import com.example.learnhub.review.infrastructure.ReviewRepository;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/reviews/**} — enrolled students rate and comment on courses they are enrolled in
 * (FR-001 to FR-006), and anyone can browse a course's reviews and aggregate rating (FR-008, FR-009).
 * Identity comes from the gateway-verified {@code X-User-Id} header, never validated here directly
 * (constitution Principle IV).
 */
@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    private static final int MAX_SIZE = 100;

    private final ReviewService reviewService;
    private final ReviewRepository reviews;

    public ReviewController(ReviewService reviewService, ReviewRepository reviews) {
        this.reviewService = reviewService;
        this.reviews = reviews;
    }

    public record ReviewsPage(Iterable<ReviewView> items, int page, int size, long totalItems) {
    }

    @PostMapping("/courses/{courseId}")
    public ResponseEntity<ReviewView> submit(@RequestHeader(IdentityHeaders.USER_ID) UUID callerId,
            @PathVariable UUID courseId, @RequestBody @Valid ReviewWriteRequest request) {
        SubmitResult result = reviewService.submit(callerId, courseId, request.rating(), request.comment());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ReviewView.of(result.review()));
    }

    /** {@code GET /api/v1/reviews/courses/{courseId}} — a course's active reviews, public (FR-008). */
    @GetMapping("/courses/{courseId}")
    public ReviewsPage list(@PathVariable UUID courseId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
        Page<Review> result = reviews.findByCourseIdAndStatus(courseId, ReviewStatus.ACTIVE, pageable);
        return new ReviewsPage(result.map(ReviewView::of).getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements());
    }

    /** {@code GET /api/v1/reviews/courses/{courseId}/summary} — a course's aggregate rating, public (FR-009). */
    @GetMapping("/courses/{courseId}/summary")
    public CourseRatingSummaryView summary(@PathVariable UUID courseId) {
        Object[] row = reviews.aggregateRating(courseId, ReviewStatus.ACTIVE).get(0);
        long reviewCount = ((Number) row[1]).longValue();
        Double averageRating = reviewCount == 0 ? null : ((Number) row[0]).doubleValue();
        return new CourseRatingSummaryView(courseId, averageRating, reviewCount);
    }

    public record CourseRatingSummaries(List<CourseRatingSummaryView> items) {
    }

    /**
     * {@code GET /api/v1/reviews/summaries?courseIds=a,b} — aggregate ratings for 1 to 50 courses in one call,
     * public (spec 008 FR-001, FR-002). One item per distinct id in request order; unreviewed courses are
     * zero-filled.
     */
    @GetMapping("/summaries")
    public CourseRatingSummaries summaries(@RequestParam(required = false) String courseIds) {
        List<UUID> ids = CourseIdBatch.parse(courseIds);
        Map<UUID, CourseRatingSummaryView> found = new HashMap<>();
        for (Object[] row : reviews.aggregateRatings(ids, ReviewStatus.ACTIVE)) {
            UUID courseId = (UUID) row[0];
            found.put(courseId, new CourseRatingSummaryView(courseId, ((Number) row[1]).doubleValue(),
                    ((Number) row[2]).longValue()));
        }
        return new CourseRatingSummaries(ids.stream()
                .map(id -> found.getOrDefault(id, new CourseRatingSummaryView(id, null, 0)))
                .toList());
    }

    /** {@code PUT /api/v1/reviews/{reviewId}} — edit the caller's own review (FR-004). */
    @PutMapping("/{reviewId}")
    public ReviewView edit(@RequestHeader(IdentityHeaders.USER_ID) UUID callerId, @PathVariable UUID reviewId,
            @RequestBody @Valid ReviewWriteRequest request) {
        Review review = reviewService.edit(reviewId, callerId, request.rating(), request.comment());
        return ReviewView.of(review);
    }

    /** {@code DELETE /api/v1/reviews/{reviewId}} — soft-delete the caller's own review (FR-005). */
    @DeleteMapping("/{reviewId}")
    public ResponseEntity<Void> delete(@RequestHeader(IdentityHeaders.USER_ID) UUID callerId,
            @PathVariable UUID reviewId) {
        reviewService.delete(reviewId, callerId);
        return ResponseEntity.noContent().build();
    }
}

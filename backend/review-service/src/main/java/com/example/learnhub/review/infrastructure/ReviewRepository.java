package com.example.learnhub.review.infrastructure;

import com.example.learnhub.review.domain.Review;
import com.example.learnhub.review.domain.ReviewStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    Optional<Review> findByUserIdAndCourseId(UUID userId, UUID courseId);

    Page<Review> findByCourseIdAndStatus(UUID courseId, ReviewStatus status, Pageable pageable);

    /**
     * Computed on read, in the same transaction as the query — no stored/materialized copy to go stale
     * (data-model.md's Course Rating Summary, research.md R1).
     */
    @Query("select avg(r.rating), count(r) from Review r where r.courseId = :courseId and r.status = :status")
    List<Object[]> aggregateRating(@Param("courseId") UUID courseId, @Param("status") ReviewStatus status);

    /** One {@code [courseId, avg, count]} row per course that has at least one review of the given status. */
    @Query("select r.courseId, avg(r.rating), count(r) from Review r where r.courseId in :courseIds "
            + "and r.status = :status group by r.courseId")
    List<Object[]> aggregateRatings(@Param("courseIds") Collection<UUID> courseIds,
            @Param("status") ReviewStatus status);
}

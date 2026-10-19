package com.example.learnhub.enrollment.infrastructure;

import com.example.learnhub.enrollment.domain.Enrollment;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {

    boolean existsByUserIdAndCourseId(UUID userId, UUID courseId);

    Optional<Enrollment> findByUserIdAndCourseId(UUID userId, UUID courseId);

    Page<Enrollment> findByUserId(UUID userId, Pageable pageable);

    /**
     * specs/005-progress-certificates-notifications research.md R1: a pessimistic write lock on the
     * enrollment row, held for the duration of a mark-complete transaction, so the progress count a
     * given request recomputes after inserting its own {@code LessonProgress} row is always accurate as
     * of that moment — not racing a concurrent sibling's own insert. Without this, two requests
     * completing the course's last two different lessons at once could each see a stale, pre-completion
     * count and neither would ever claim completion.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Enrollment e WHERE e.userId = :userId AND e.courseId = :courseId")
    Optional<Enrollment> findByUserIdAndCourseIdForUpdate(@Param("userId") UUID userId,
            @Param("courseId") UUID courseId);

    /**
     * research.md R1: the conditional update that lets "every lesson complete, write one
     * course.completed event" hold under concurrency — only the caller whose update actually changes a
     * row (return value 1) is the one that should write the outbox row.
     */
    @Modifying
    @Query("UPDATE Enrollment e SET e.completedAt = :now WHERE e.id = :id AND e.completedAt IS NULL")
    int markCompletedIfNotAlready(@Param("id") UUID id, @Param("now") Instant now);
}

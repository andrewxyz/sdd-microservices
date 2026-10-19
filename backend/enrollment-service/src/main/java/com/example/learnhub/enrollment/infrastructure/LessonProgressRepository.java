package com.example.learnhub.enrollment.infrastructure;

import com.example.learnhub.enrollment.domain.LessonProgress;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LessonProgressRepository extends JpaRepository<LessonProgress, UUID> {

    boolean existsByEnrollmentIdAndLessonId(UUID enrollmentId, UUID lessonId);

    long countByEnrollmentId(UUID enrollmentId);

    @Modifying
    @Query("DELETE FROM LessonProgress p WHERE p.enrollmentId = :enrollmentId AND p.lessonId = :lessonId")
    void deleteByEnrollmentIdAndLessonId(@Param("enrollmentId") UUID enrollmentId, @Param("lessonId") UUID lessonId);
}

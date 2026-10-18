package com.example.learnhub.enrollment.infrastructure;

import com.example.learnhub.enrollment.domain.Enrollment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {

    boolean existsByUserIdAndCourseId(UUID userId, UUID courseId);

    Optional<Enrollment> findByUserIdAndCourseId(UUID userId, UUID courseId);

    Page<Enrollment> findByUserId(UUID userId, Pageable pageable);
}

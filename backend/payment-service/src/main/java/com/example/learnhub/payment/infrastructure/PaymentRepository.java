package com.example.learnhub.payment.infrastructure;

import com.example.learnhub.payment.domain.Payment;
import com.example.learnhub.payment.domain.PaymentStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    boolean existsByUserIdAndCourseIdAndStatus(UUID userId, UUID courseId, PaymentStatus status);

    Page<Payment> findByUserId(UUID userId, Pageable pageable);
}

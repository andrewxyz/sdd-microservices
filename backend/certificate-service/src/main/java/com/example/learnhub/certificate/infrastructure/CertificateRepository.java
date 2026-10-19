package com.example.learnhub.certificate.infrastructure;

import com.example.learnhub.certificate.domain.Certificate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CertificateRepository extends JpaRepository<Certificate, UUID> {

    boolean existsByUserIdAndCourseId(UUID userId, UUID courseId);

    Page<Certificate> findByUserId(UUID userId, Pageable pageable);

    boolean existsByVerificationCode(String verificationCode);

    Optional<Certificate> findByVerificationCode(String verificationCode);
}

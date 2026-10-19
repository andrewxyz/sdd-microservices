package com.example.learnhub.certificate.api;

import com.example.learnhub.certificate.domain.Certificate;
import java.time.Instant;
import java.util.UUID;

/** A caller's own certificate (contracts/certificate-service.openapi.yaml CertificateView). */
public record CertificateView(UUID id, UUID courseId, String courseTitle, String verificationCode,
        Instant issuedAt) {

    public static CertificateView of(Certificate certificate) {
        return new CertificateView(certificate.getId(), certificate.getCourseId(), certificate.getCourseTitle(),
                certificate.getVerificationCode(), certificate.getIssuedAt());
    }
}

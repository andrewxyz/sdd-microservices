package com.example.learnhub.certificate.api;

import com.example.learnhub.certificate.domain.Certificate;
import java.time.Instant;

/**
 * The public, holder-blind verification result (contracts/certificate-service.openapi.yaml
 * VerificationView, FR-017): deliberately excludes {@code userId} and any other holder-identifying field.
 */
public record VerificationView(boolean valid, String courseTitle, Instant issuedAt) {

    public static VerificationView of(Certificate certificate) {
        return new VerificationView(true, certificate.getCourseTitle(), certificate.getIssuedAt());
    }
}

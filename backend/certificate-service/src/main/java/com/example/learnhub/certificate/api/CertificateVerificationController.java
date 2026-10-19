package com.example.learnhub.certificate.api;

import com.example.learnhub.certificate.domain.Certificate;
import com.example.learnhub.certificate.infrastructure.CertificateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /api/v1/certificates/verify/{code}} — public, anonymous, holder-blind (FR-015 to FR-018).
 * Reads no identity header: this is the one certificate-service path the gateway exposes without auth.
 */
@RestController
@RequestMapping("/api/v1/certificates")
public class CertificateVerificationController {

    private final CertificateRepository certificates;

    public CertificateVerificationController(CertificateRepository certificates) {
        this.certificates = certificates;
    }

    @GetMapping("/verify/{code}")
    public VerificationView verify(@PathVariable String code) {
        Certificate certificate = certificates.findByVerificationCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such certificate."));
        return VerificationView.of(certificate);
    }
}

package com.example.learnhub.certificate.api;

import com.example.learnhub.certificate.domain.Certificate;
import com.example.learnhub.certificate.infrastructure.CertificateRepository;
import com.example.learnhub.common.identity.IdentityHeaders;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/certificates/mine} — the caller's own certificates, never anyone else's (FR-013, FR-014). */
@RestController
@RequestMapping("/api/v1/certificates")
public class CertificateController {

    private static final int MAX_SIZE = 100;

    private final CertificateRepository certificates;

    public CertificateController(CertificateRepository certificates) {
        this.certificates = certificates;
    }

    public record CertificatesPage(Iterable<CertificateView> items, int page, int size, long totalItems) {
    }

    @GetMapping("/mine")
    public CertificatesPage mine(@RequestHeader(IdentityHeaders.USER_ID) UUID callerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
        Page<Certificate> result = certificates.findByUserId(callerId, pageable);
        return new CertificatesPage(result.map(CertificateView::of).getContent(), result.getNumber(),
                result.getSize(), result.getTotalElements());
    }
}

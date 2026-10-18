package com.example.learnhub.enrollment.api;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.enrollment.domain.Enrollment;
import com.example.learnhub.enrollment.infrastructure.EnrollmentRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/enrollments} — the caller's own enrollments, never anyone else's (FR-018). */
@RestController
@RequestMapping("/api/v1/enrollments")
public class EnrollmentController {

    private static final int MAX_SIZE = 100;

    private final EnrollmentRepository enrollments;

    public EnrollmentController(EnrollmentRepository enrollments) {
        this.enrollments = enrollments;
    }

    public record EnrollmentsPage(Iterable<EnrollmentView> items, int page, int size, long totalItems) {
    }

    @GetMapping
    public EnrollmentsPage myEnrollments(@RequestHeader(IdentityHeaders.USER_ID) UUID callerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
        Page<Enrollment> result = enrollments.findByUserId(callerId, pageable);
        return new EnrollmentsPage(result.map(EnrollmentView::of).getContent(), result.getNumber(),
                result.getSize(), result.getTotalElements());
    }
}

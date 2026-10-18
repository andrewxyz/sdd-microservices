package com.example.learnhub.payment.api;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.payment.domain.Payment;
import com.example.learnhub.payment.infrastructure.PaymentRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /api/v1/payments} and {@code GET /api/v1/payments/{id}} — a user's own payment history and
 * a single payment's status, never anyone else's (FR-017, FR-019, FR-020).
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private static final int MAX_SIZE = 100;

    private final PaymentRepository payments;

    public PaymentController(PaymentRepository payments) {
        this.payments = payments;
    }

    public record PaymentsPage(Iterable<PaymentView> items, int page, int size, long totalItems) {
    }

    @GetMapping
    public PaymentsPage myPayments(@RequestHeader(IdentityHeaders.USER_ID) UUID callerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
        Page<Payment> result = payments.findByUserId(callerId, pageable);
        return new PaymentsPage(result.map(PaymentView::of).getContent(), result.getNumber(),
                result.getSize(), result.getTotalElements());
    }

    @GetMapping("/{paymentId}")
    public PaymentView byId(@RequestHeader(IdentityHeaders.USER_ID) UUID callerId, @PathVariable UUID paymentId) {
        Payment payment = payments.findById(paymentId)
                .filter(p -> p.getUserId().equals(callerId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such payment."));
        return PaymentView.of(payment);
    }
}

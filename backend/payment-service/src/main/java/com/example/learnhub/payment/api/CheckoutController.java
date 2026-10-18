package com.example.learnhub.payment.api;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.payment.application.CheckoutService;
import com.example.learnhub.payment.application.CheckoutService.CheckoutOutcome;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/v1/checkouts} (FR-001 to FR-012). */
@RestController
@RequestMapping("/api/v1/checkouts")
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping
    public ResponseEntity<PaymentView> checkout(@RequestHeader(IdentityHeaders.USER_ID) java.util.UUID callerId,
            @Valid @RequestBody CheckoutRequest request) {
        CheckoutOutcome outcome = checkoutService.checkout(callerId, request.courseId(),
                request.idempotencyKey(), request.paymentToken());
        HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(PaymentView.of(outcome.payment()));
    }
}

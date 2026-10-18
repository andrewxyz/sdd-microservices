package com.example.learnhub.payment.application;

import com.example.learnhub.payment.domain.OutboxEvent;
import com.example.learnhub.payment.domain.Payment;
import com.example.learnhub.payment.domain.PaymentStatus;
import com.example.learnhub.payment.domain.PaymentSucceededPayload;
import com.example.learnhub.payment.infrastructure.CourseLookupClient;
import com.example.learnhub.payment.infrastructure.CourseSummaryResponse;
import com.example.learnhub.payment.infrastructure.OutboxEventRepository;
import com.example.learnhub.payment.infrastructure.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Checkout for a single course (FR-001 to FR-012, research.md R1 to R3, R9). A free course resolves
 * immediately with no payment-provider involvement (FR-005); a paid course is resolved through
 * {@link SimulatedPaymentProvider}; a success writes an {@link OutboxEvent} in the same transaction so
 * the enrollment it implies is never lost (research.md R1).
 *
 * <p>Uses an explicit {@link TransactionTemplate} rather than {@code @Transactional} so the
 * save-then-possibly-retry flow (research.md R9) can cleanly start a *new* transaction to re-read after
 * a unique-constraint violation aborts the one that lost the race — a self-invoked {@code @Transactional}
 * method on the same bean would not get its own proxy-managed transaction for that second attempt.
 */
@Service
public class CheckoutService {

    private static final String PUBLISHED = "PUBLISHED";

    private final PaymentRepository payments;
    private final OutboxEventRepository outboxEvents;
    private final CourseLookupClient courseLookupClient;
    private final SimulatedPaymentProvider paymentProvider;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper eventMapper = com.example.learnhub.events.EventMapper.create();

    public CheckoutService(PaymentRepository payments, OutboxEventRepository outboxEvents,
            CourseLookupClient courseLookupClient, SimulatedPaymentProvider paymentProvider,
            PlatformTransactionManager transactionManager) {
        this.payments = payments;
        this.outboxEvents = outboxEvents;
        this.courseLookupClient = courseLookupClient;
        this.paymentProvider = paymentProvider;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Whether a checkout call created a new payment, or merely replayed an already-resolved one. */
    public record CheckoutOutcome(Payment payment, boolean replayed) {
    }

    public CheckoutOutcome checkout(UUID userId, UUID courseId, String idempotencyKey, String paymentToken) {
        Payment existing = payments.findByUserIdAndIdempotencyKey(userId, idempotencyKey).orElse(null);
        if (existing != null) {
            return new CheckoutOutcome(requireSameRequest(existing, courseId, paymentToken), true);
        }

        CourseSummaryResponse course = courseLookupClient.summary(courseId);
        if (course == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such course.");
        }
        if (!PUBLISHED.equals(course.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This course is not available for checkout.");
        }
        if (course.instructorId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot check out your own course.");
        }
        if (payments.existsByUserIdAndCourseIdAndStatus(userId, courseId, PaymentStatus.SUCCEEDED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You are already enrolled in this course.");
        }

        Instant now = Instant.now();
        Payment payment = new Payment(UUID.randomUUID(), userId, courseId, idempotencyKey, paymentToken, now);

        int priceCents = course.priceCents() == null ? 0 : course.priceCents();
        if (priceCents == 0) {
            payment.resolveSucceeded(0, now);
        } else {
            if (paymentToken == null || paymentToken.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "A payment token is required for a paid course.");
            }
            resolveWithProvider(payment, priceCents, paymentToken, now);
        }

        try {
            transactionTemplate.executeWithoutResult(status -> {
                payments.save(payment);
                if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
                    recordOutboxEvent(payment, now);
                }
                payments.flush();
            });
        } catch (DataIntegrityViolationException raceLost) {
            return resolveAfterRace(userId, courseId, idempotencyKey, paymentToken);
        }
        return new CheckoutOutcome(payment, false);
    }

    /**
     * research.md R9: another request won the race first commit. If it won under the *same*
     * idempotency key, replay its result (the point of idempotency); otherwise it won the course's
     * partial-unique "one succeeded payment" slot under a different key, so this request is refused.
     * Runs in a fresh transaction — the one that just failed is already aborted.
     */
    private CheckoutOutcome resolveAfterRace(UUID userId, UUID courseId, String idempotencyKey, String paymentToken) {
        Payment winner = transactionTemplate.execute(status ->
                payments.findByUserIdAndIdempotencyKey(userId, idempotencyKey).orElse(null));
        if (winner != null) {
            return new CheckoutOutcome(requireSameRequest(winner, courseId, paymentToken), true);
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "You are already enrolled in this course.");
    }

    private void resolveWithProvider(Payment payment, int priceCents, String paymentToken, Instant now) {
        SimulatedPaymentProvider.Outcome outcome = paymentProvider.charge(paymentToken);
        switch (outcome) {
            case SimulatedPaymentProvider.Outcome.Succeeded ignored -> payment.resolveSucceeded(priceCents, now);
            case SimulatedPaymentProvider.Outcome.Declined declined -> payment.resolveDeclined(declined.reason(), now);
            case SimulatedPaymentProvider.Outcome.Failed failed -> payment.resolveFailed(failed.reason(), now);
        }
    }

    private void recordOutboxEvent(Payment payment, Instant now) {
        PaymentSucceededPayload payload = PaymentSucceededPayload.of(payment);
        String json;
        try {
            json = eventMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize payment.succeeded payload", e);
        }
        outboxEvents.save(new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), payment.getId(), json, now));
    }

    private Payment requireSameRequest(Payment existing, UUID courseId, String paymentToken) {
        boolean sameCourse = existing.getCourseId().equals(courseId);
        boolean sameToken = Objects.equals(existing.getPaymentToken(), paymentToken);
        if (!sameCourse || !sameToken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This idempotency key was already used for a different request.");
        }
        return existing;
    }
}

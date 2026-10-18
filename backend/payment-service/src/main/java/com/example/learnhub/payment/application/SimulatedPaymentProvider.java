package com.example.learnhub.payment.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * A pure, in-process function of the caller-supplied test token — not a network call (research.md R2).
 * There is no real external payment provider to simulate talking to in this feature; only a chooseable
 * outcome (FR-007).
 */
@Component
public class SimulatedPaymentProvider {

    public static final String TOKEN_SUCCESS = "sim-success";
    public static final String TOKEN_DECLINE = "sim-decline";
    public static final String TOKEN_UNAVAILABLE = "sim-unavailable";

    /** The outcome of running a payment token through the simulated provider. */
    public sealed interface Outcome {
        record Succeeded() implements Outcome {
        }

        record Declined(String reason) implements Outcome {
        }

        record Failed(String reason) implements Outcome {
        }
    }

    public Outcome charge(String paymentToken) {
        return switch (paymentToken) {
            case TOKEN_SUCCESS -> new Outcome.Succeeded();
            case TOKEN_DECLINE -> new Outcome.Declined("The payment was declined.");
            case TOKEN_UNAVAILABLE -> new Outcome.Failed("The payment provider was temporarily unavailable.");
            default -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unrecognized payment token.");
        };
    }
}

package com.example.learnhub.analytics.infrastructure;

/** Course ownership could not be confirmed; the request fails with 503 and shows no numbers. */
public class OwnershipUnavailableException extends RuntimeException {

    public OwnershipUnavailableException(String what, Throwable cause) {
        super("Could not confirm ownership of " + what, cause);
    }
}

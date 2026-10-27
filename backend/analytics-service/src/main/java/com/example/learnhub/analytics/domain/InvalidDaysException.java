package com.example.learnhub.analytics.domain;

/** The {@code days} query parameter was not 7, 30 or 90. */
public class InvalidDaysException extends InvalidParameterException {

    public InvalidDaysException(String message) {
        super("days", message);
    }
}

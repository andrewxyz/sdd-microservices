package com.example.learnhub.analytics.domain;

/** A request parameter was not acceptable; mapped to a 400 whose {@code fieldErrors} names the parameter. */
public class InvalidParameterException extends IllegalArgumentException {

    private final String field;

    public InvalidParameterException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}

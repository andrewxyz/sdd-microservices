package com.example.learnhub.events;

import java.util.regex.Pattern;

/**
 * The naming rule for routing keys on the {@code learning.events} exchange: {@code <domain>.<event>},
 * lowercase, for example {@code payment.succeeded}.
 *
 * <p>No domain routing key is declared here. Each key belongs to the spec and service that owns that
 * domain, so that this shared library stays free of domain knowledge (constitution Principle II).
 */
public final class RoutingKeys {

    /** Must stay in step with the {@code type} pattern in event-envelope.schema.json. */
    public static final String PATTERN = "^[a-z][a-z0-9]*\\.[a-z][a-z0-9_]*$";

    private static final Pattern COMPILED = Pattern.compile(PATTERN);

    private RoutingKeys() {
    }

    /** True when {@code key} is a well-formed {@code <domain>.<event>} routing key. */
    public static boolean isValid(String key) {
        return key != null && COMPILED.matcher(key).matches();
    }
}

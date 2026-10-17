package com.example.learnhub.common.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The one definition of what a correlation ID looks like, shared by the gateway and every servlet
 * service so they cannot disagree about which client values are acceptable.
 *
 * <p>FR-020: a client-supplied value is reused only if it is well formed; anything else is replaced
 * by a generated one. The pattern is deliberately narrow because the value ends up in logs and in
 * outgoing headers.
 */
public final class CorrelationIds {

    /** Request and response header carrying the correlation ID. */
    public static final String HEADER = "X-Correlation-Id";

    /** MDC key, so structured log lines carry the ID without any per-service code. */
    public static final String MDC_KEY = "correlationId";

    /** Must stay in step with the correlationId pattern in the published contracts. */
    public static final String PATTERN = "^[A-Za-z0-9._-]{8,64}$";

    private static final Pattern COMPILED = Pattern.compile(PATTERN);

    private CorrelationIds() {
    }

    /** True when a client-supplied value may be reused as-is. */
    public static boolean isValid(String candidate) {
        return candidate != null && COMPILED.matcher(candidate).matches();
    }

    /** A fresh ID that always satisfies {@link #isValid}. */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /** The client's value when it is well formed, otherwise a freshly generated one. */
    public static String resolve(String candidate) {
        return isValid(candidate) ? candidate : generate();
    }
}

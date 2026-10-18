package com.example.learnhub.common.identity;

/**
 * The headers the gateway uses to pass the caller's identity to a destination service
 * (specs/002-user-auth FR-020). Shared between api-gateway (which sets them) and every business
 * service (which reads them), so the two sides cannot disagree about the header names.
 */
public final class IdentityHeaders {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLE = "X-User-Role";

    private IdentityHeaders() {
    }
}

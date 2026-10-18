package com.example.learnhub.gateway.security;

/**
 * The outcome of {@link JwtVerifier#verify(String)}. Exactly one of {@code accountId}/{@code role} or
 * {@code failure} is present; never both, never neither.
 *
 * <p>Deliberately carries no detail about *why* verification failed beyond this coarse reason: FR-018
 * requires one fixed 401 response regardless of cause, and a finer-grained message risks leaking
 * whether a token is malformed vs. tampered vs. simply unknown, which is of no use to a legitimate
 * caller and some use to an attacker.
 */
public record VerifiedToken(String accountId, String role, Reason failure) {

    public enum Reason {
        MISSING,
        MALFORMED,
        TAMPERED,
        EXPIRED
    }

    public static VerifiedToken valid(String accountId, String role) {
        return new VerifiedToken(accountId, role, null);
    }

    public static VerifiedToken invalid(Reason reason) {
        return new VerifiedToken(null, null, reason);
    }

    public boolean isValid() {
        return failure == null;
    }
}

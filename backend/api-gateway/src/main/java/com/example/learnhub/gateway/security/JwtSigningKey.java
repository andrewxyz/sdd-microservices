package com.example.learnhub.gateway.security;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the same HS256 secret auth-service signs with (research.md R1). Spring already fails startup
 * if {@code JWT_SIGNING_SECRET} is absent; this additionally fails fast if it is present but too short
 * to be a sound HS256 key (plan.md Constraints: at least 32 bytes) — the gateway must not start able to
 * "verify" tokens with a trivially weak key (spec.md Edge Cases: signing material unavailable).
 */
@Component
public class JwtSigningKey {

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKeySpec key;

    public JwtSigningKey(@Value("${JWT_SIGNING_SECRET}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SIGNING_SECRET must be at least " + MIN_SECRET_BYTES + " bytes, was " + bytes.length);
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    public SecretKeySpec key() {
        return key;
    }
}

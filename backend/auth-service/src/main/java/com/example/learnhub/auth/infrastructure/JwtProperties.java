package com.example.learnhub.auth.infrastructure;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the HS256 signing secret shared with api-gateway (research.md R1). Spring already fails
 * startup if {@code JWT_SIGNING_SECRET} is absent (no default on the placeholder); this additionally
 * fails fast if it is present but too short to be a sound HS256 key (plan.md Constraints: at least 32
 * bytes), rather than silently issuing weakly-signed tokens.
 */
@Component
public class JwtProperties {

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKeySpec signingKey;

    public JwtProperties(@Value("${JWT_SIGNING_SECRET}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SIGNING_SECRET must be at least " + MIN_SECRET_BYTES + " bytes, was " + bytes.length);
        }
        this.signingKey = new SecretKeySpec(bytes, "HmacSHA256");
    }

    public SecretKeySpec signingKey() {
        return signingKey;
    }
}

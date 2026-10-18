package com.example.learnhub.auth.infrastructure;

import com.example.learnhub.auth.domain.Role;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Signs access tokens (HS256, research.md R1, R9). Claims are exactly {@code sub}, {@code role},
 * {@code iat}, {@code exp} per {@code contracts/jwt-claims.schema.json}; {@code role} is the account's
 * role at the moment of issuance and is not re-checked until the next login (spec.md Clarifications,
 * FR-021).
 */
@Component
public class JwtIssuer {

    /** FR-006: access tokens expire no more than 15 minutes after issuance. */
    public static final long ACCESS_TOKEN_TTL_SECONDS = 900;

    private final JwtProperties properties;

    public JwtIssuer(JwtProperties properties) {
        this.properties = properties;
    }

    public String issue(UUID accountId, Role role) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(ACCESS_TOKEN_TTL_SECONDS);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(accountId.toString())
                .claim("role", role.name())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(properties.signingKey().getEncoded()));
        } catch (JOSEException e) {
            // The only way this fails is a key too short for HS256, already rejected by JwtProperties
            // at startup, so this should be unreachable; fail loudly rather than silently return null.
            throw new IllegalStateException("Failed to sign access token", e);
        }
        return jwt.serialize();
    }
}

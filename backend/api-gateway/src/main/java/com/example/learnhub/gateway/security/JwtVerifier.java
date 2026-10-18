package com.example.learnhub.gateway.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Verifies an access token's HS256 signature and expiry (research.md R1, R9). Never includes the token
 * value, or any substring of it, in an exception message or log line (FR-024): every failure path below
 * returns a {@link VerifiedToken.Reason}, nothing derived from the token's actual content.
 */
@Component
public class JwtVerifier {

    private final JwtSigningKey signingKey;

    public JwtVerifier(JwtSigningKey signingKey) {
        this.signingKey = signingKey;
    }

    public VerifiedToken verify(String token) {
        if (token == null || token.isBlank()) {
            return VerifiedToken.invalid(VerifiedToken.Reason.MISSING);
        }

        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException e) {
            return VerifiedToken.invalid(VerifiedToken.Reason.MALFORMED);
        }

        try {
            if (!jwt.verify(new MACVerifier(signingKey.key().getEncoded()))) {
                return VerifiedToken.invalid(VerifiedToken.Reason.TAMPERED);
            }
        } catch (JOSEException e) {
            return VerifiedToken.invalid(VerifiedToken.Reason.TAMPERED);
        }

        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            return VerifiedToken.invalid(VerifiedToken.Reason.MALFORMED);
        }

        if (claims.getExpirationTime() == null || claims.getExpirationTime().toInstant().isBefore(Instant.now())) {
            return VerifiedToken.invalid(VerifiedToken.Reason.EXPIRED);
        }

        String subject = claims.getSubject();
        String role = claims.getClaim("role") instanceof String s ? s : null;
        if (subject == null || role == null) {
            return VerifiedToken.invalid(VerifiedToken.Reason.MALFORMED);
        }

        return VerifiedToken.valid(subject, role);
    }
}

package com.example.learnhub.auth.application;

import com.example.learnhub.auth.domain.RefreshTokenRecord;
import com.example.learnhub.auth.infrastructure.RefreshTokenRepository;
import com.example.learnhub.auth.infrastructure.RefreshTokens;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Logout (FR-010): revokes exactly the caller's own session, leaving every other session untouched. */
@Service
public class RevokeSessionService {

    private final RefreshTokenRepository refreshTokens;
    private final RefreshTokens refreshTokenGenerator;

    public RevokeSessionService(RefreshTokenRepository refreshTokens, RefreshTokens refreshTokenGenerator) {
        this.refreshTokens = refreshTokens;
        this.refreshTokenGenerator = refreshTokenGenerator;
    }

    public void revoke(String presentedRefreshToken, UUID callerAccountId) {
        String hash = refreshTokenGenerator.hash(presentedRefreshToken);
        RefreshTokenRecord record = refreshTokens.findByTokenHash(hash).orElse(null);

        if (record == null || !record.getAccountId().equals(callerAccountId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token.");
        }

        record.revoke(Instant.now());
        refreshTokens.save(record);
    }
}

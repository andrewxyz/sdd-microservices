package com.example.learnhub.auth.application;

import com.example.learnhub.auth.domain.Account;
import com.example.learnhub.auth.domain.RefreshTokenRecord;
import com.example.learnhub.auth.infrastructure.AccountRepository;
import com.example.learnhub.auth.infrastructure.JwtIssuer;
import com.example.learnhub.auth.infrastructure.RefreshTokenRepository;
import com.example.learnhub.auth.infrastructure.RefreshTokens;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Refresh token rotation (FR-009, FR-011; research.md R2). A token is accepted only while
 * {@code usedAt == null && revokedAt == null && expiresAt > now} (data-model.md RefreshTokenRecord);
 * any other presentation — already used, revoked, expired, or simply unknown — is refused identically.
 */
@Service
public class RotateRefreshTokenService {

    private final RefreshTokenRepository refreshTokens;
    private final AccountRepository accounts;
    private final RefreshTokens refreshTokenGenerator;
    private final JwtIssuer jwtIssuer;

    public RotateRefreshTokenService(RefreshTokenRepository refreshTokens, AccountRepository accounts,
            RefreshTokens refreshTokenGenerator, JwtIssuer jwtIssuer) {
        this.refreshTokens = refreshTokens;
        this.accounts = accounts;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.jwtIssuer = jwtIssuer;
    }

    public TokenPair rotate(String presentedRefreshToken) {
        String hash = refreshTokenGenerator.hash(presentedRefreshToken);
        RefreshTokenRecord record = refreshTokens.findByTokenHash(hash).orElse(null);

        Instant now = Instant.now();
        if (record == null || !record.isUsable(now)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token.");
        }

        Account account = accounts.findById(record.getAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token."));

        record.markUsed(now);
        refreshTokens.save(record);

        String accessToken = jwtIssuer.issue(account.getId(), account.getRole());
        String rawNewRefreshToken = refreshTokenGenerator.generate();
        RefreshTokenRecord newRecord = new RefreshTokenRecord(
                UUID.randomUUID(), account.getId(), refreshTokenGenerator.hash(rawNewRefreshToken),
                now, now.plus(AuthenticateService.REFRESH_TOKEN_TTL));
        refreshTokens.save(newRecord);

        return TokenPair.of(accessToken, rawNewRefreshToken);
    }
}

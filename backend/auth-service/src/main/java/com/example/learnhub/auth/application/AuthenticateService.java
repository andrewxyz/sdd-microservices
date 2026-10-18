package com.example.learnhub.auth.application;

import com.example.learnhub.auth.domain.Account;
import com.example.learnhub.auth.domain.RefreshTokenRecord;
import com.example.learnhub.auth.infrastructure.AccountRepository;
import com.example.learnhub.auth.infrastructure.JwtIssuer;
import com.example.learnhub.auth.infrastructure.LoginAttemptWindow;
import com.example.learnhub.auth.infrastructure.PasswordHasher;
import com.example.learnhub.auth.infrastructure.RefreshTokenRepository;
import com.example.learnhub.auth.infrastructure.RefreshTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Login (FR-005 to FR-009). Wrong password and unknown email produce the identical generic failure
 * (FR-007); both count toward throttling the same way, so the throttling behavior itself cannot be used
 * to tell the two cases apart either.
 */
@Service
public class AuthenticateService {

    /** FR-006: refresh tokens expire no more than 30 days after issuance. */
    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    private final AccountRepository accounts;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordHasher passwordHasher;
    private final RefreshTokens refreshTokenGenerator;
    private final JwtIssuer jwtIssuer;
    private final LoginAttemptWindow attemptWindow;

    public AuthenticateService(AccountRepository accounts, RefreshTokenRepository refreshTokens,
            PasswordHasher passwordHasher, RefreshTokens refreshTokenGenerator, JwtIssuer jwtIssuer,
            LoginAttemptWindow attemptWindow) {
        this.accounts = accounts;
        this.refreshTokens = refreshTokens;
        this.passwordHasher = passwordHasher;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.jwtIssuer = jwtIssuer;
        this.attemptWindow = attemptWindow;
    }

    public TokenPair login(String email, String password, String sourceIp) {
        String normalizedEmail = email.toLowerCase();

        if (attemptWindow.isThrottled(normalizedEmail) || attemptWindow.isThrottled(sourceIp)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed attempts. Try again later.");
        }

        Account account = accounts.findByEmailIgnoreCase(normalizedEmail).orElse(null);
        if (account == null || !passwordHasher.matches(password, account.getPasswordHash())) {
            // FR-007: identical outcome regardless of which part failed.
            attemptWindow.recordFailure(normalizedEmail);
            attemptWindow.recordFailure(sourceIp);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }

        attemptWindow.clear(normalizedEmail);
        return issueTokenPair(account);
    }

    TokenPair issueTokenPair(Account account) {
        String accessToken = jwtIssuer.issue(account.getId(), account.getRole());

        String rawRefreshToken = refreshTokenGenerator.generate();
        Instant now = Instant.now();
        RefreshTokenRecord record = new RefreshTokenRecord(
                UUID.randomUUID(), account.getId(), refreshTokenGenerator.hash(rawRefreshToken),
                now, now.plus(REFRESH_TOKEN_TTL));
        refreshTokens.save(record);

        return TokenPair.of(accessToken, rawRefreshToken);
    }
}

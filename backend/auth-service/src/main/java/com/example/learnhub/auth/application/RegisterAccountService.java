package com.example.learnhub.auth.application;

import com.example.learnhub.auth.domain.Account;
import com.example.learnhub.auth.domain.PasswordStrengthRule;
import com.example.learnhub.auth.domain.Role;
import com.example.learnhub.auth.infrastructure.AccountRepository;
import com.example.learnhub.auth.infrastructure.PasswordHasher;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Registration (FR-001 to FR-004). */
@Service
public class RegisterAccountService {

    private final AccountRepository accounts;
    private final PasswordHasher passwordHasher;

    public RegisterAccountService(AccountRepository accounts, PasswordHasher passwordHasher) {
        this.accounts = accounts;
        this.passwordHasher = passwordHasher;
    }

    public Account register(String email, String password) {
        String normalizedEmail = email.toLowerCase();

        if (accounts.existsByEmailIgnoreCase(normalizedEmail)) {
            // FR-002: refuse without revealing whether the conflict was case-only.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists.");
        }

        PasswordStrengthRule.violation(password).ifPresent(reason -> {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
        });

        Account account = new Account(
                UUID.randomUUID(), normalizedEmail, passwordHasher.hash(password), Role.STUDENT, Instant.now());
        return accounts.save(account);
    }
}

package com.example.learnhub.auth.application;

import com.example.learnhub.auth.domain.Account;
import com.example.learnhub.auth.domain.Role;
import com.example.learnhub.auth.infrastructure.AccountRepository;
import com.example.learnhub.auth.infrastructure.PasswordHasher;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * FR-015: on first startup against an empty database, creates exactly one administrator account from
 * configuration. Idempotent (research.md R6): if an administrator already exists, does nothing and does
 * not re-read the configured values into that account, mirroring the platform-foundation db-init
 * script's own idempotency pattern.
 */
@Component
public class BootstrapAdministrator implements ApplicationRunner {

    private final AccountRepository accounts;
    private final PasswordHasher passwordHasher;
    private final String adminEmail;
    private final String adminPassword;

    public BootstrapAdministrator(AccountRepository accounts, PasswordHasher passwordHasher,
            @Value("${ADMIN_EMAIL}") String adminEmail, @Value("${ADMIN_PASSWORD}") String adminPassword) {
        this.accounts = accounts;
        this.passwordHasher = passwordHasher;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accounts.countByRole(Role.ADMINISTRATOR) > 0) {
            return;
        }
        if (adminEmail == null || adminEmail.isBlank()) {
            throw new IllegalStateException("ADMIN_EMAIL is required to bootstrap the first administrator");
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalStateException("ADMIN_PASSWORD is required to bootstrap the first administrator");
        }

        Account admin = new Account(UUID.randomUUID(), adminEmail.toLowerCase(),
                passwordHasher.hash(adminPassword), Role.ADMINISTRATOR, Instant.now());
        accounts.save(admin);
    }
}

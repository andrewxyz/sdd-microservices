package com.example.learnhub.auth.application;

import com.example.learnhub.auth.domain.Account;
import com.example.learnhub.auth.infrastructure.AccountRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * specs/005-progress-certificates-notifications research.md R4: the service-to-service account lookup,
 * reached directly (never through the gateway, which has no route for this path at all) by
 * notification-service via {@code lb://auth-service}.
 */
@Service
public class AccountLookupService {

    private final AccountRepository accounts;

    public AccountLookupService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public Account find(UUID accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account."));
    }
}

package com.example.learnhub.auth.api;

import com.example.learnhub.auth.application.AccountLookupService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/internal/accounts/{accountId}} — specs/005-progress-certificates-notifications
 * research.md R4. No gateway route targets this path (research.md R9); reachable only by
 * notification-service calling {@code lb://auth-service} directly.
 */
@RestController
@RequestMapping("/api/v1/internal/accounts")
public class InternalAccountController {

    private final AccountLookupService accountLookupService;

    public InternalAccountController(AccountLookupService accountLookupService) {
        this.accountLookupService = accountLookupService;
    }

    @GetMapping("/{accountId}")
    public AccountSummaryView summary(@PathVariable UUID accountId) {
        return AccountSummaryView.of(accountLookupService.find(accountId));
    }
}

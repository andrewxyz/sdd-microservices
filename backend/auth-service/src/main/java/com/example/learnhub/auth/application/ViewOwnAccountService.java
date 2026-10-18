package com.example.learnhub.auth.application;

import com.example.learnhub.auth.domain.Account;
import com.example.learnhub.auth.infrastructure.AccountRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** FR-023: the caller's own profile, never including password or token data. */
@Service
public class ViewOwnAccountService {

    private final AccountRepository accounts;

    public ViewOwnAccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public Account view(UUID callerAccountId) {
        return accounts.findById(callerAccountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account."));
    }
}

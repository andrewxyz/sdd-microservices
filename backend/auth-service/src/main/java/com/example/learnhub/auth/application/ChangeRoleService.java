package com.example.learnhub.auth.application;

import com.example.learnhub.auth.domain.Account;
import com.example.learnhub.auth.domain.Role;
import com.example.learnhub.auth.infrastructure.AccountRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * FR-013, FR-014: who may call this is already enforced by the gateway (the {@code auth-admin} route
 * requires the ADMINISTRATOR role, research.md R5); this service only enforces the invariant the
 * gateway cannot see — that the platform is never left with zero administrators.
 */
@Service
public class ChangeRoleService {

    private final AccountRepository accounts;

    public ChangeRoleService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public void changeRole(UUID targetAccountId, Role newRole) {
        Account target = accounts.findById(targetAccountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account."));

        boolean losingLastAdministrator = target.getRole() == Role.ADMINISTRATOR
                && newRole != Role.ADMINISTRATOR
                && accounts.countByRole(Role.ADMINISTRATOR) <= 1;
        if (losingLastAdministrator) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This change would leave the platform with no administrator accounts.");
        }

        target.setRole(newRole);
        accounts.save(target);
    }
}

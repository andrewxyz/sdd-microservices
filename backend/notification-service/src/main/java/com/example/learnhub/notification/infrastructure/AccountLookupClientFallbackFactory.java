package com.example.learnhub.notification.infrastructure;

import java.util.UUID;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * research.md R5: throws on every failure — a lookup failure must fail the *message* so it retries,
 * never silently skip or guess an email address.
 */
@Component
public class AccountLookupClientFallbackFactory implements FallbackFactory<AccountLookupClient> {

    @Override
    public AccountLookupClient create(Throwable cause) {
        return accountId -> {
            throw new LookupUnavailableException("account", accountId, cause);
        };
    }
}

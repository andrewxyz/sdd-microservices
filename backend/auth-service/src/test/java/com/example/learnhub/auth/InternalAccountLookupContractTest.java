package com.example.learnhub.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.auth.domain.Account;
import com.example.learnhub.auth.domain.Role;
import com.example.learnhub.auth.infrastructure.AccountRepository;
import com.example.learnhub.auth.support.AbstractAuthServiceIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Provider-side contract test (specs/005-progress-certificates-notifications plan.md Testing): asserts
 * the real {@code GET /api/v1/internal/accounts/{id}} response has exactly the shape
 * {@code contracts/auth-service-internal.openapi.yaml} promises — closing the gap a purely
 * consumer-side WireMock stub can never catch (the real provider drifting from what the stub assumes).
 */
class InternalAccountLookupContractTest extends AbstractAuthServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accounts;

    @Test
    void theRealResponseHasExactlyIdAndEmail() throws Exception {
        Account account = accounts.save(new Account(UUID.randomUUID(), "contract-check@example.com",
                "irrelevant-hash", Role.STUDENT, Instant.now()));

        mockMvc.perform(get("/api/v1/internal/accounts/" + account.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(account.getId().toString()))
                .andExpect(jsonPath("$.email").value("contract-check@example.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.role").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist());
    }

    @Test
    void anUnknownAccountIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/internal/accounts/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}

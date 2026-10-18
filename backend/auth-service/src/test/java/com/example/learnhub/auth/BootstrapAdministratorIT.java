package com.example.learnhub.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.auth.application.BootstrapAdministrator;
import com.example.learnhub.auth.domain.Role;
import com.example.learnhub.auth.infrastructure.AccountRepository;
import com.example.learnhub.auth.support.AbstractAuthServiceIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * FR-015, research.md R6: exactly one administrator exists after startup, created from configuration;
 * running the bootstrap step again (as a restart would) creates no second one and leaves the first
 * unchanged.
 */
class BootstrapAdministratorIT extends AbstractAuthServiceIT {

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private BootstrapAdministrator bootstrapAdministrator;

    @Test
    void exactlyOneAdministratorExistsAfterStartup() {
        // The shared context (support/AbstractAuthServiceIT) already ran BootstrapAdministrator once,
        // at its own startup, before any test method here runs.
        assertThat(accounts.countByRole(Role.ADMINISTRATOR)).isEqualTo(1);
    }

    @Test
    void runningBootstrapAgainCreatesNoSecondAdministrator() {
        long before = accounts.countByRole(Role.ADMINISTRATOR);

        bootstrapAdministrator.run(null);

        assertThat(accounts.countByRole(Role.ADMINISTRATOR)).isEqualTo(before);
    }
}

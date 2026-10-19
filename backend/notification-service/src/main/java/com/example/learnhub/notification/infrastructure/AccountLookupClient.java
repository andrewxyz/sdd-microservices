package com.example.learnhub.notification.infrastructure;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Calls auth-service's internal lookup to resolve a recipient's email (research.md R4, R5), never
 * through the gateway.
 *
 * <p>{@code url} is empty in production, so the client load-balances via {@code lb://auth-service} as
 * usual; tests override {@code auth-service.url} directly (not
 * {@code spring.cloud.openfeign.client.config.auth-service.url}, which Spring Cloud LoadBalancer does
 * not reliably honor, research.md finding). Setting {@code url} here makes
 * {@link org.springframework.cloud.openfeign.FeignClientFactoryBean} build a plain, non-load-balanced
 * target, deterministically bypassing the load balancer.
 */
@FeignClient(name = "auth-service", url = "${auth-service.url:}",
        fallbackFactory = AccountLookupClientFallbackFactory.class)
public interface AccountLookupClient {

    @GetMapping("/api/v1/internal/accounts/{accountId}")
    AccountSummaryResponse summary(@PathVariable("accountId") UUID accountId);
}

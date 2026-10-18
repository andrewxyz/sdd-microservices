package com.example.learnhub.auth.api;

import com.example.learnhub.auth.application.RegisterAccountService;
import com.example.learnhub.auth.domain.Account;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/v1/auth/register} — public (FR-016). */
@RestController
@RequestMapping("/api/v1/auth")
public class RegisterController {

    private final RegisterAccountService registerAccountService;

    public RegisterController(RegisterAccountService registerAccountService) {
        this.registerAccountService = registerAccountService;
    }

    public record RegisterRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record AccountView(UUID id, String email, String role, Instant createdAt) {
        static AccountView of(Account account) {
            return new AccountView(account.getId(), account.getEmail(), account.getRole().name(),
                    account.getCreatedAt());
        }
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView register(@RequestBody RegisterRequest request) {
        Account account = registerAccountService.register(request.email(), request.password());
        return AccountView.of(account);
    }
}

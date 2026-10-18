package com.example.learnhub.auth.api;

import com.example.learnhub.auth.application.AuthenticateService;
import com.example.learnhub.auth.application.TokenPair;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/v1/auth/login} — public (FR-016). */
@RestController
@RequestMapping("/api/v1/auth")
public class LoginController {

    private final AuthenticateService authenticateService;

    public LoginController(AuthenticateService authenticateService) {
        this.authenticateService = authenticateService;
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    @PostMapping("/login")
    public TokenPair login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authenticateService.login(request.email(), request.password(), httpRequest.getRemoteAddr());
    }
}

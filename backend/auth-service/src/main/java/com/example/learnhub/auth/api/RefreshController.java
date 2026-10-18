package com.example.learnhub.auth.api;

import com.example.learnhub.auth.application.RotateRefreshTokenService;
import com.example.learnhub.auth.application.TokenPair;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/v1/auth/refresh} — public (FR-016). */
@RestController
@RequestMapping("/api/v1/auth")
public class RefreshController {

    private final RotateRefreshTokenService rotateRefreshTokenService;

    public RefreshController(RotateRefreshTokenService rotateRefreshTokenService) {
        this.rotateRefreshTokenService = rotateRefreshTokenService;
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    @PostMapping("/refresh")
    public TokenPair refresh(@RequestBody RefreshRequest request) {
        return rotateRefreshTokenService.rotate(request.refreshToken());
    }
}

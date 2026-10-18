package com.example.learnhub.auth.api;

import com.example.learnhub.auth.application.RevokeSessionService;
import com.example.learnhub.common.identity.IdentityHeaders;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/v1/auth/logout} — protected (falls under the {@code auth} catch-all route). */
@RestController
@RequestMapping("/api/v1/auth")
public class LogoutController {

    private final RevokeSessionService revokeSessionService;

    public LogoutController(RevokeSessionService revokeSessionService) {
        this.revokeSessionService = revokeSessionService;
    }

    public record LogoutRequest(@NotBlank String refreshToken) {
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody LogoutRequest request,
            @RequestHeader(IdentityHeaders.USER_ID) UUID callerAccountId) {
        revokeSessionService.revoke(request.refreshToken(), callerAccountId);
    }
}

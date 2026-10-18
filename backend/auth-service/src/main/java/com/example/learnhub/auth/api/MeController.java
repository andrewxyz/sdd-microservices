package com.example.learnhub.auth.api;

import com.example.learnhub.auth.api.RegisterController.AccountView;
import com.example.learnhub.auth.application.ViewOwnAccountService;
import com.example.learnhub.common.identity.IdentityHeaders;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/auth/me} — protected (falls under the {@code auth} catch-all route). */
@RestController
@RequestMapping("/api/v1/auth")
public class MeController {

    private final ViewOwnAccountService viewOwnAccountService;

    public MeController(ViewOwnAccountService viewOwnAccountService) {
        this.viewOwnAccountService = viewOwnAccountService;
    }

    @GetMapping("/me")
    public AccountView me(@RequestHeader(IdentityHeaders.USER_ID) UUID callerAccountId) {
        return AccountView.of(viewOwnAccountService.view(callerAccountId));
    }
}

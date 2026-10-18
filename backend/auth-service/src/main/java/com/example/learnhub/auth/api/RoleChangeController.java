package com.example.learnhub.auth.api;

import com.example.learnhub.auth.application.ChangeRoleService;
import com.example.learnhub.auth.domain.Role;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PATCH /api/v1/auth/accounts/{accountId}/role} — protected, ADMINISTRATOR only. Role
 * enforcement happens at the gateway (the {@code auth-admin} route, research.md R5), not here.
 */
@RestController
@RequestMapping("/api/v1/auth/accounts")
public class RoleChangeController {

    private final ChangeRoleService changeRoleService;

    public RoleChangeController(ChangeRoleService changeRoleService) {
        this.changeRoleService = changeRoleService;
    }

    public record ChangeRoleRequest(@NotNull Role role) {
    }

    @PatchMapping("/{accountId}/role")
    public void changeRole(@PathVariable UUID accountId, @RequestBody ChangeRoleRequest request) {
        changeRoleService.changeRole(accountId, request.role());
    }
}

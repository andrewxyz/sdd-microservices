package com.example.learnhub.analytics.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who is calling, from the gateway's identity headers. The gateway already restricts
 * {@code /api/v1/instructor/**} to these two roles; services trust those headers and published ports can
 * bypass the gateway, so this check repeats the rule where the data is (constitution VII).
 */
public record CallerContext(UUID userId, Role role) {

    public enum Role { INSTRUCTOR, ADMINISTRATOR }

    public static CallerContext require(String userIdHeader, String roleHeader) {
        Role role = parseRole(roleHeader);
        UUID userId = parseUserId(userIdHeader);
        return new CallerContext(userId, role);
    }

    public boolean isAdministrator() {
        return role == Role.ADMINISTRATOR;
    }

    private static Role parseRole(String roleHeader) {
        if ("INSTRUCTOR".equals(roleHeader)) {
            return Role.INSTRUCTOR;
        }
        if ("ADMINISTRATOR".equals(roleHeader)) {
            return Role.ADMINISTRATOR;
        }
        throw notAllowed();
    }

    private static UUID parseUserId(String userIdHeader) {
        try {
            return UUID.fromString(userIdHeader);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw notAllowed();
        }
    }

    private static ResponseStatusException notAllowed() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to view the dashboard.");
    }
}

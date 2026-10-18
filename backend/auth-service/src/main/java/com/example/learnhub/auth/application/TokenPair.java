package com.example.learnhub.auth.application;

import com.example.learnhub.auth.infrastructure.JwtIssuer;

/** FR-005, FR-009: an access token and a refresh token, issued together at login or at rotation. */
public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {

    public static TokenPair of(String accessToken, String refreshToken) {
        return new TokenPair(accessToken, refreshToken, JwtIssuer.ACCESS_TOKEN_TTL_SECONDS);
    }
}

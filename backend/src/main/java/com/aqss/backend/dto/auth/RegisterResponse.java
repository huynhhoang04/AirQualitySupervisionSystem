package com.aqss.backend.dto.auth;

public record RegisterResponse(
        Long id,
        String email,
        String role
) {
}

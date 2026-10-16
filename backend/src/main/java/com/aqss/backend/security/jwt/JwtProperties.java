package com.aqss.backend.security.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** JWT signing and lifetime settings (prefix {@code security.jwt}). */
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(
        String secret,
        long accessTokenExpirationMs,
        long refreshTokenExpirationMs
) {
}

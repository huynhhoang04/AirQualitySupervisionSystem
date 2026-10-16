package com.aqss.backend.security.jwt;

import com.aqss.backend.entity.User;
import com.aqss.backend.entity.UserRole;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdefghijklmnop";

    private static User user() {
        return User.builder().id(1L).email("admin@aqss.local").role(UserRole.ROLE_ADMIN).isActive(true).build();
    }

    @Test
    @DisplayName("An issued access token round-trips subject, role and type")
    void should_RoundTripClaims_When_TokenIssued() {
        JwtService jwtService = new JwtService(new JwtProperties(SECRET, 1_800_000, 604_800_000));

        String token = jwtService.generateAccessToken(user());
        Claims claims = jwtService.parse(token);

        assertThat(jwtService.isValid(token)).isTrue();
        assertThat(claims.getSubject()).isEqualTo("admin@aqss.local");
        assertThat(claims.get(JwtService.CLAIM_ROLE, String.class)).isEqualTo("ROLE_ADMIN");
        assertThat(claims.get(JwtService.CLAIM_TYPE, String.class)).isEqualTo(JwtService.TYPE_ACCESS);
        assertThat(jwtService.accessTokenExpirationSeconds()).isEqualTo(1800);
    }

    @Test
    @DisplayName("Refresh tokens are distinguishable from access tokens")
    void should_MarkTokenType_When_RefreshTokenIssued() {
        JwtService jwtService = new JwtService(new JwtProperties(SECRET, 1_800_000, 604_800_000));

        Claims claims = jwtService.parse(jwtService.generateRefreshToken(user()));

        assertThat(claims.get(JwtService.CLAIM_TYPE, String.class)).isEqualTo(JwtService.TYPE_REFRESH);
    }

    @Test
    @DisplayName("An already-expired token is rejected")
    void should_RejectToken_When_Expired() {
        JwtService jwtService = new JwtService(new JwtProperties(SECRET, -1_000, -1_000));

        assertThat(jwtService.isValid(jwtService.generateAccessToken(user()))).isFalse();
    }

    @Test
    @DisplayName("A token signed with a different secret is rejected")
    void should_RejectToken_When_SignedWithAnotherSecret() {
        JwtService issuer = new JwtService(new JwtProperties(SECRET, 1_800_000, 604_800_000));
        JwtService verifier = new JwtService(new JwtProperties("another-secret-0123456789abcdefghijklmnop", 1_800_000, 604_800_000));

        assertThat(verifier.isValid(issuer.generateAccessToken(user()))).isFalse();
    }
}

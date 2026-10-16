package com.aqss.backend.service;

import com.aqss.backend.dto.auth.LoginRequest;
import com.aqss.backend.dto.auth.RefreshRequest;
import com.aqss.backend.dto.auth.RegisterRequest;
import com.aqss.backend.dto.auth.RegisterResponse;
import com.aqss.backend.dto.auth.TokenResponse;
import com.aqss.backend.entity.User;
import com.aqss.backend.entity.UserRole;
import com.aqss.backend.exception.BadRequestException;
import com.aqss.backend.exception.ConflictException;
import com.aqss.backend.repository.UserRepository;
import com.aqss.backend.security.jwt.JwtService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Registration, login and refresh-token rotation (BCrypt + stateless JWT). */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("Email already registered: " + request.email());
        }
        User user = User.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .role(UserRole.ROLE_USER)
                .isActive(true)
                .createdAt(Instant.now())
                .build();
        User saved = userRepository.save(user);
        return new RegisterResponse(saved.getId(), saved.getEmail(), saved.getRole().name());
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .orElseThrow(() -> new BadRequestException("Invalid email or password"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadRequestException("Invalid email or password");
        }
        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse refresh(RefreshRequest request) {
        if (!jwtService.isValid(request.refreshToken())) {
            throw new BadRequestException("Invalid or expired refresh token");
        }
        Claims claims = jwtService.parse(request.refreshToken());
        if (!JwtService.TYPE_REFRESH.equals(claims.get(JwtService.CLAIM_TYPE, String.class))) {
            throw new BadRequestException("Provided token is not a refresh token");
        }
        User user = userRepository.findByEmail(claims.getSubject())
                .orElseThrow(() -> new BadRequestException("Unknown user in refresh token"));
        return issueTokens(user);
    }

    private TokenResponse issueTokens(User user) {
        return new TokenResponse(
                jwtService.generateAccessToken(user),
                jwtService.generateRefreshToken(user),
                "Bearer",
                jwtService.accessTokenExpirationSeconds());
    }
}

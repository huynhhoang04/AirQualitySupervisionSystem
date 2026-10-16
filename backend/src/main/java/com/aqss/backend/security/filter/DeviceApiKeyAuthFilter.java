package com.aqss.backend.security.filter;

import com.aqss.backend.repository.DeviceRepository;
import com.aqss.backend.security.ApiKeyHasher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates device requests via the {@code X-API-Key} header (SHA-256 hash lookup),
 * granting {@code ROLE_DEVICE}. Skips when a user is already authenticated by the JWT filter.
 */
@Component
@RequiredArgsConstructor
public class DeviceApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";

    private final DeviceRepository deviceRepository;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader(HEADER);
        if (apiKey != null && !apiKey.isBlank()
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            deviceRepository.findByApiKeyHash(ApiKeyHasher.sha256Hex(apiKey)).ifPresent(device -> {
                var authentication = new UsernamePasswordAuthenticationToken(
                        device.getDeviceId(), null, List.of(new SimpleGrantedAuthority("ROLE_DEVICE")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        filterChain.doFilter(request, response);
    }
}

package com.aqss.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Guards {@code /internal/**} with a bearer token.
 *
 * <p>The AI worker runs on a laptop and calls the cloud backend outbound; the browser never sees
 * this API. A blank token is allowed so local development works without ceremony — production sets
 * {@code AI_WORKER_TOKEN} and closes the door.
 */
@Component
@RequiredArgsConstructor
public class InternalAuthFilter extends OncePerRequestFilter {

    private final AqssSettings settings;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/internal/")) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!settings.ai().enabled()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String token = settings.ai().workerToken();
        if (token != null && !token.isBlank()) {
            String header = request.getHeader("Authorization");
            String expected = "Bearer " + token;
            if (header == null || !constantTimeEquals(header, expected)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}

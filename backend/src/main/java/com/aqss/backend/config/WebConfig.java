package com.aqss.backend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the dashboard.
 *
 * <p>This must be an MVC configuration: a {@code CorsConfigurationSource} bean is a Spring Security
 * interface and, with Security removed, nothing consumes it — the browser then sees no
 * {@code Access-Control-Allow-Origin} and blocks every call.
 *
 * <p>{@code CORS_ORIGIN} takes a comma-separated list, because the dashboard is served from the
 * deployed static site while a developer still runs Vite on 5173/5174. Allow-listing one origin at
 * a time means every port change is a silent 403 that the UI can only report as "no devices".
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AqssSettings settings;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = settings.cors().origin().split("\\s*,\\s*");
        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}

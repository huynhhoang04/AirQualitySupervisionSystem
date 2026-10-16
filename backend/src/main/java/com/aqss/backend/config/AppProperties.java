package com.aqss.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Application-level settings (prefix {@code app}). */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Cors cors) {

    /** CORS policy for the browser dashboard. */
    public record Cors(String origin) {
    }
}

package com.aqss.backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Prints which credential source the datasource actually uses.
 *
 * <p>pgjdbc lets parameters in the JDBC URL win over the ones passed as connection properties.
 * A Neon "copy snippet" URL therefore silently overrides {@code DB_USER}/{@code DB_PASS}: changing
 * the password in the dashboard has no effect and PostgreSQL answers
 * {@code password authentication failed}. This one-time log makes that visible.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DatasourceDiagnostics implements ApplicationRunner {

    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        String url = environment.getProperty("spring.datasource.url", "");
        String user = environment.getProperty("spring.datasource.username", "");
        String password = environment.getProperty("spring.datasource.password", "");

        String masked = url.replaceAll("(?i)(password=)[^&]*", "$1***");
        boolean urlCarriesCredentials =
                url.toLowerCase().contains("user=") || url.toLowerCase().contains("password=");

        log.info("JDBC target: {}", masked);
        log.info("Credentials: user='{}' password={}", user,
                password.isEmpty() ? "EMPTY (falling back to the built-in default!)"
                        : "set (" + password.length() + " chars)");

        if (urlCarriesCredentials) {
            log.warn("DB_URL contains user=/password= — pgjdbc uses those and ignores DB_USER/DB_PASS. "
                    + "Remove them from DB_URL (keep ?sslmode=require only) and set DB_USER/DB_PASS instead.");
        }
        if (password.isEmpty()) {
            log.warn("DB_PASS is empty — the datasource uses its default password, which no Neon role has.");
        }
    }
}

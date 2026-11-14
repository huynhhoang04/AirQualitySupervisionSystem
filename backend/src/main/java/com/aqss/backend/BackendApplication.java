package com.aqss.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AQSS V6 — Layer 2 (the brain).
 *
 * <p>Collect telemetry with provenance over MQTT, serve the dashboard, drive the filter fan, and
 * keep the metadata + forecast ledger for an optional local AI worker.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(BackendApplication.class);
        application.addListeners(
                (ApplicationListener<ApplicationEnvironmentPreparedEvent>) BackendApplication::printDatasourceDiagnostics);
        application.run(args);
    }

    /**
     * Prints what the datasource will really use, before the context is created. A failing Flyway
     * migration aborts startup, so a bean (ApplicationRunner) would never get to log anything.
     *
     * <p>Two values matter and they are not always the same: the raw environment variable, and the
     * resolved {@code spring.datasource.password} (placeholder defaults, env groups, or a URL that
     * embeds credentials can all change it).
     */
    private static void printDatasourceDiagnostics(ApplicationEnvironmentPreparedEvent event) {
        Environment environment = event.getEnvironment();

        String envUrl = System.getenv("DB_URL");
        String envUser = System.getenv("DB_USER");
        String envPassword = System.getenv("DB_PASS");

        String url = environment.getProperty("spring.datasource.url", "");
        String user = environment.getProperty("spring.datasource.username", "");
        String password = environment.getProperty("spring.datasource.password", "");

        System.out.println("[DATASOURCE] env   url=" + mask(envUrl) + " user=" + envUser
                + " password=" + describe(envPassword));
        System.out.println("[DATASOURCE] final url=" + mask(url) + " user='" + user + "'"
                + " password=" + describe(password));

        if (envPassword != null && !envPassword.equals(password)) {
            System.out.println("[DATASOURCE] WARNING: the resolved password differs from DB_PASS — "
                    + "something overrides spring.datasource.password.");
        }
        boolean urlCarriesCredentials =
                url.toLowerCase().contains("user=") || url.toLowerCase().contains("password=");
        if (urlCarriesCredentials) {
            System.out.println("[DATASOURCE] WARNING: DB_URL carries user=/password= — pgjdbc uses "
                    + "those and ignores DB_USER/DB_PASS.");
        }
        if (password.isEmpty()) {
            System.out.println("[DATASOURCE] WARNING: the effective password is EMPTY.");
        }
        if ("true".equalsIgnoreCase(System.getenv("AQSS_DEBUG_CREDENTIALS"))) {
            // Temporary switch: prints the exact string handed to the driver so it can be compared
            // character by character with the database UI. Turn it off after diagnosing.
            System.out.println("[DATASOURCE] DEBUG password='" + password + "' length="
                    + password.length());
        }
    }

    private static String mask(String url) {
        return url == null ? "<unset>" : url.replaceAll("(?i)(password=)[^&]*", "$1***");
    }

    /** Length plus a few characters, enough to spot a truncated paste or a trailing newline. */
    private static String describe(String value) {
        if (value == null) {
            return "<unset>";
        }
        if (value.isEmpty()) {
            return "EMPTY";
        }
        String head = value.substring(0, Math.min(4, value.length()));
        String tail = value.length() > 6 ? value.substring(value.length() - 2) : "";
        return value.length() + " chars (" + head + "…" + tail + ")"
                + (value.endsWith("\n") || value.endsWith("\r") ? " [TRAILING NEWLINE!]" : "")
                + (value.startsWith(" ") || value.endsWith(" ") ? " [SPACE!]" : "");
    }
}

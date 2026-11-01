package com.aqss.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AQSS V2 — Layer 2 (the brain).
 *
 * <p>Collect telemetry over MQTT, serve it to the dashboard, drive the filter fan, and call
 * Layer 3 (threshold / dosage / forecast). No auth, no partitioned telemetry.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class BackendApplication {

    public static void main(String[] args) {
        printDatasourceDiagnostics();
        SpringApplication.run(BackendApplication.class, args);
    }

    /**
     * Prints the datasource target and the shape of the credentials before Spring starts — a
     * failing Flyway migration aborts the context, so nothing that runs as a bean would be logged.
     *
     * <p>pgjdbc gives parameters in the JDBC URL precedence over the connection properties, so a
     * DB_URL copied from a Neon snippet (it embeds {@code user=} / {@code password=}) silently
     * overrides DB_USER/DB_PASS. That is the usual cause of "password authentication failed".
     */
    private static void printDatasourceDiagnostics() {
        String url = System.getenv("DB_URL");
        if (url == null || url.isBlank()) {
            url = "jdbc:postgresql://" + env("DB_HOST", "localhost") + ":" + env("DB_PORT", "5432")
                    + "/" + env("DB_NAME", "aqss_db");
        }
        String user = env("DB_USER", "aqss_user");
        String password = System.getenv("DB_PASS");

        String masked = url.replaceAll("(?i)(password=)[^&]*", "$1***");
        System.out.println("[DATASOURCE] url=" + masked);
        System.out.println("[DATASOURCE] user=" + user + " password=" + describe(password));
        if (url.toLowerCase().contains("user=") || url.toLowerCase().contains("password=")) {
            System.out.println("[DATASOURCE] WARNING: DB_URL carries user=/password= — pgjdbc uses them "
                    + "and ignores DB_USER/DB_PASS. Remove them (keep only ?sslmode=require).");
        }
        if (password == null || password.isEmpty()) {
            System.out.println("[DATASOURCE] WARNING: DB_PASS is not set — the built-in default is used.");
        }
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

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}

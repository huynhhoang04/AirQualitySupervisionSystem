package com.aqss.backend.config;

import com.aqss.backend.entity.User;
import com.aqss.backend.entity.UserRole;
import com.aqss.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Seeds the first admin on an empty installation so the dashboard is usable out of the box.
 *
 * <p>Fully environment-driven ({@code ADMIN_EMAIL} / {@code ADMIN_PASSWORD}) and skipped when the
 * email is empty, so a deployment can choose to register the first account through the API
 * instead. Nothing is seeded once any user exists.
 */
@Component
@Slf4j
public class AdminSeeder implements ApplicationRunner {

    /** The password the local profile ships with; keeping it outside local dev is an incident. */
    static final String INSECURE_DEFAULT_PASSWORD = "admin12345";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    public AdminSeeder(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       @Value("${aqss.admin.email:}") String adminEmail,
                       @Value("${aqss.admin.password:}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminEmail == null || adminEmail.isBlank()) {
            log.info("Admin seeding disabled (aqss.admin.email is empty)");
            return;
        }
        if (userRepository.count() > 0) {
            return;   // never touch an installation that already has users
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            log.error("Not seeding {}: aqss.admin.password is empty. Set ADMIN_PASSWORD or "
                    + "register the first account via POST /api/v1/auth/register.", adminEmail);
            return;
        }

        userRepository.save(User.builder()
                .email(adminEmail)
                .passwordHash(passwordEncoder.encode(adminPassword))
                .fullName("AQSS Admin")
                .role(UserRole.ROLE_ADMIN)
                .isActive(true)
                .createdAt(Instant.now())
                .build());

        if (INSECURE_DEFAULT_PASSWORD.equals(adminPassword)) {
            log.warn("Seeded admin {} with the DEFAULT password — set ADMIN_PASSWORD before any "
                    + "real deployment", adminEmail);
        } else {
            log.info("Seeded admin {}", adminEmail);
        }
    }
}

package com.blockevidence.backend.config;

import java.time.Clock;
import java.util.Locale;

import com.blockevidence.backend.model.User;
import com.blockevidence.backend.repository.UserRepository;
import com.blockevidence.backend.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Development-only bootstrap. Phase 1 has no user-creation endpoint (admin user management, A4, is
 * not scheduled yet), so without this nobody could log in. Creates one user per role, e.g.
 * collector@blockevidence.local, all sharing one password.
 *
 * <p>Runs only under the {@code dev} profile, and the password comes from the environment
 * (constraint C-07). If BLOCKEVIDENCE_DEV_SEED_PASSWORD is unset it seeds nothing rather than
 * invent a password. Idempotent: existing users are left untouched.
 */
@Component
@Profile("dev")
public class DevUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevUserSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String seedPassword;

    public DevUserSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder, Clock clock,
            @Value("${blockevidence.dev-seed.password:}") String seedPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.seedPassword = seedPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedPassword.isBlank()) {
            log.warn("dev profile active but BLOCKEVIDENCE_DEV_SEED_PASSWORD is not set: no users seeded");
            return;
        }
        String hash = passwordEncoder.encode(seedPassword);
        for (Role role : Role.values()) {
            String email = role.name().toLowerCase(Locale.ROOT).replace('_', '-') + "@blockevidence.local";
            if (!userRepository.existsByEmailIgnoreCase(email)) {
                userRepository.save(new User(email, hash, "Dev " + role.name(), "Development", role, clock.instant()));
                log.info("Seeded dev user {}", email);
            }
        }
    }
}

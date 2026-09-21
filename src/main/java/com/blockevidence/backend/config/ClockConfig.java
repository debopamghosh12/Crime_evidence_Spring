package com.blockevidence.backend.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One injectable Clock so token expiry and rotation logic can be tested with a fixed time. This is
 * the *server* clock, used for auth only. Evidence timestamps must come from the ledger (C4).
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}

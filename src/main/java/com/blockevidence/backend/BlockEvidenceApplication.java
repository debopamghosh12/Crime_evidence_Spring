package com.blockevidence.backend;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// @ConfigurationPropertiesScan picks up the *Properties records in config/ (K3), so each one needs
// no separate @EnableConfigurationProperties registration.
@SpringBootApplication
@ConfigurationPropertiesScan
public class BlockEvidenceApplication {

    public static void main(String[] args) {
        // The PostgreSQL JDBC driver sends the JVM's default zone to the server on every connection.
        // A legacy alias such as "Asia/Calcutta" (the default on some Windows/India setups) is
        // rejected by PostgreSQL 16 with 'invalid value for parameter "TimeZone"', so the app would
        // not start. Pinning UTC removes the dependency on the host's zone and matches the
        // Instant/TIMESTAMPTZ columns used everywhere. Must run before any connection is opened.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(BlockEvidenceApplication.class, args);
    }

}

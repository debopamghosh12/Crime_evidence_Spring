package com.blockevidence.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.blockevidence.backend.TestSecrets;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

/** C-07 / K3: the app must refuse to start on a missing or weak signing secret. */
class JwtPropertiesTest {

    final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    JwtProperties withSecret(String secret) {
        return new JwtProperties(secret, Duration.ofMinutes(15), Duration.ofDays(7), "be");
    }

    @Test
    void emptySecretIsRejected() {
        assertThat(validator.validate(withSecret(""))).isNotEmpty();
    }

    @Test
    void shortSecretIsRejected() {
        assertThat(validator.validate(withSecret("x".repeat(31)))).isNotEmpty();
    }

    @Test
    void thirtyTwoCharacterSecretIsAccepted() {
        assertThat(validator.validate(withSecret("x".repeat(32)))).isEmpty();
    }

    @Test
    void toStringNeverPrintsTheSecret() {
        assertThat(withSecret(TestSecrets.JWT_SECRET).toString())
                .doesNotContain(TestSecrets.JWT_SECRET)
                .contains("redacted");
    }
}

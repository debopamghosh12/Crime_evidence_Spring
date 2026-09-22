package com.blockevidence.backend;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Signing keys for tests, generated fresh on every run so that no key literal exists in the
 * repository (constraint C-07). Each is 73 characters, comfortably over the 32-character minimum.
 */
public final class TestSecrets {

    public static final String JWT_SECRET = UUID.randomUUID() + "-" + UUID.randomUUID();
    public static final String OTHER_SECRET = UUID.randomUUID() + "-" + UUID.randomUUID();

    /** F2/F3, CONSTRAINTS.md C-10: a fresh random AES-256 master key, base64, for tests that build a real
     *  {@code MasterKey}/{@code UserKeyService} - never a literal, same reasoning as JWT_SECRET above. */
    public static final String ENCRYPTION_MASTER_KEY = randomAes256Base64();

    private TestSecrets() {
    }

    private static String randomAes256Base64() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}

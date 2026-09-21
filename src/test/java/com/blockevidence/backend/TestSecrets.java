package com.blockevidence.backend;

import java.util.UUID;

/**
 * Signing keys for tests, generated fresh on every run so that no key literal exists in the
 * repository (constraint C-07). Each is 73 characters, comfortably over the 32-character minimum.
 */
public final class TestSecrets {

    public static final String JWT_SECRET = UUID.randomUUID() + "-" + UUID.randomUUID();
    public static final String OTHER_SECRET = UUID.randomUUID() + "-" + UUID.randomUUID();

    private TestSecrets() {
    }
}

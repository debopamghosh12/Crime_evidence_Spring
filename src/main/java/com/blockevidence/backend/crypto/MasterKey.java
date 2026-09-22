package com.blockevidence.backend.crypto;

import java.util.Base64;

import com.blockevidence.backend.config.EncryptionProperties;
import org.springframework.stereotype.Component;

/**
 * F2/F3, CONSTRAINTS.md C-10: the one AES-256 key that protects every user's stored private key at rest
 * (never the evidence content itself - that is the per-evidence ECK, wrapped per user, section 3 of the
 * design). Decoded and length-checked once at startup so a misconfigured key fails fast (C-07's "refuse to
 * start" rule, same as the JWT secret) rather than surfacing as a confusing crypto error on the first upload.
 */
@Component
public class MasterKey {

    private final byte[] bytes;

    public MasterKey(EncryptionProperties properties) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(properties.masterKey());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "blockevidence.encryption.master-key (BLOCKEVIDENCE_ENCRYPTION_MASTER_KEY) is not valid base64", e);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    "blockevidence.encryption.master-key must decode to exactly 32 bytes (AES-256), got "
                            + decoded.length);
        }
        this.bytes = decoded;
    }

    /** A defensive copy: callers must not be able to mutate the key held here. */
    byte[] bytes() {
        return bytes.clone();
    }
}

package com.blockevidence.backend.crypto;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F2/F3 (docs/F2_F3_ENVELOPE_ENCRYPTION_DESIGN.md sections 3-5): generates, wraps, re-wraps and revokes each
 * evidence item's content key (ECK). The raw ECK is never persisted; it exists in memory only for the duration
 * of one wrap/unwrap call (design section 1 - the same trust tier already accepted for Fabric identities, now
 * spelled out for content keys in CONSTRAINTS.md C-10).
 */
@Service
public class ContentKeyService {

    private static final Logger log = LoggerFactory.getLogger(ContentKeyService.class);
    private static final String WRAP_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final int ECK_LENGTH_BITS = 256;

    private final EvidenceContentKeyRepository repository;
    private final UserKeyService userKeys;
    private final Clock clock;

    public ContentKeyService(EvidenceContentKeyRepository repository, UserKeyService userKeys, Clock clock) {
        this.repository = repository;
        this.userKeys = userKeys;
        this.clock = clock;
    }

    /** A fresh, random AES-256 key - one per evidence item (design section 3), never derived from anything. */
    public byte[] newKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(ECK_LENGTH_BITS);
            return generator.generateKey().getEncoded();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("AES is not available", e);
        }
    }

    /**
     * Mandatory wrap for the registering user (design section 5): called inside {@code EvidenceService.register}'s
     * pre-ledger try block, so a failure here (e.g. the registrant has no provisioned keypair) aborts registration
     * and unpins, the same as any other pre-ledger storage failure - an evidence item must never be created with
     * nobody able to read it.
     */
    @Transactional
    public void wrapForRegistrant(String evidenceId, UUID registrantId, byte[] eck) {
        wrap(evidenceId, registrantId, eck);
    }

    /**
     * Best-effort wrap for an existing case member other than the registrant, at register time. Logged and
     * skipped on failure, not fatal to registration (design section 5 and section 8's stated limit) - matches
     * this project's established compensation-not-blocking pattern (D-024/D-037, E3).
     */
    @Transactional
    public void wrapBestEffort(String evidenceId, UUID userId, byte[] eck) {
        try {
            wrap(evidenceId, userId, eck);
        } catch (RuntimeException e) {
            log.warn("Could not wrap the content key of {} for user {}: {}", evidenceId, userId, e.toString());
        }
    }

    /**
     * F3's "re-wrap" (design section 5): access was just granted to {@code newUserId} (a case member added, or a
     * custody transfer's receiver). Recovers the raw ECK via ANY existing wrapped copy for this evidence item -
     * unwrapping that holder's row with their own (master-key-decrypted) private key - then wraps it fresh under
     * {@code newUserId}'s public key. Possible only because the backend already custodies every private key
     * (C-10); no cooperation from the existing holder is needed. Never throws: called from event-processing and
     * case-membership paths that must not fail because a key backfill had a hiccup.
     */
    @Transactional
    public void reWrapForNewUser(String evidenceId, UUID newUserId) {
        try {
            if (repository.existsByEvidenceIdAndUserId(evidenceId, newUserId)) {
                return; // already has a wrapped copy - nothing to do
            }
            EvidenceContentKey existing = repository.findFirstByEvidenceId(evidenceId).orElse(null);
            if (existing == null) {
                log.warn("Cannot re-wrap the content key of {} for user {}: no existing wrapped copy to recover it from",
                        evidenceId, newUserId);
                return;
            }
            byte[] eck = unwrapWith(existing.getWrappedKey(), userKeys.privateKey(existing.getUserId()));
            wrap(evidenceId, newUserId, eck);
        } catch (RuntimeException e) {
            log.warn("Could not re-wrap the content key of {} for user {}: {}", evidenceId, newUserId, e.toString());
        }
    }

    /**
     * F3's "revoke" (design section 5): deletes the user's wrapped copy. Does not rotate the ECK or touch
     * content already on IPFS - a deliberate, stated limit (design section 8): this is forward-only, the same
     * shape of residual already accepted for A2's certificate revocation (no CRL).
     */
    @Transactional
    public void revoke(String evidenceId, UUID userId) {
        repository.deleteByEvidenceIdAndUserId(evidenceId, userId);
    }

    /** Empty means "not authorised" - callers turn that into 403 KEY_NOT_AUTHORISED, never an exception here. */
    @Transactional(readOnly = true)
    public Optional<byte[]> unwrap(String evidenceId, UUID userId) {
        return repository.findByEvidenceIdAndUserId(evidenceId, userId)
                .map(row -> unwrapWith(row.getWrappedKey(), userKeys.privateKey(userId)));
    }

    private void wrap(String evidenceId, UUID userId, byte[] eck) {
        if (repository.existsByEvidenceIdAndUserId(evidenceId, userId)) {
            return; // idempotent: e.g. the registrant is also already a case member
        }
        PublicKey publicKey = userKeys.publicKey(userId);
        byte[] wrapped = rsaOaep(Cipher.ENCRYPT_MODE, publicKey, eck);
        repository.save(new EvidenceContentKey(evidenceId, userId, wrapped, clock.instant()));
    }

    private static byte[] unwrapWith(byte[] wrappedKey, PrivateKey privateKey) {
        return rsaOaep(Cipher.DECRYPT_MODE, privateKey, wrappedKey);
    }

    private static byte[] rsaOaep(int mode, java.security.Key key, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(WRAP_TRANSFORMATION);
            cipher.init(mode, key);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA-OAEP " + (mode == Cipher.ENCRYPT_MODE ? "wrap" : "unwrap") + " failed", e);
        }
    }
}

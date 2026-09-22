package com.blockevidence.backend.crypto;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * F2/F3 section 3-4: one user's wrapped copy of one evidence item's content key (ECK). {@code wrappedKey} is
 * {@code RSA-OAEP-SHA256(that user's public key, the raw 32-byte ECK)} - "wrapped per authorised user" (F2's
 * own wording) means literally this: a distinct RSA ciphertext per user, not a shared secret gated by an
 * application check. Unique per (evidenceId, userId); a revoke (section 5, CaseService.removeMember) deletes
 * the row rather than marking it inactive - there is nothing left to protect once it is gone, and F3 draws no
 * distinction between "revoked" and "never granted".
 */
@Entity
@Table(name = "evidence_content_keys")
public class EvidenceContentKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "evidence_id", nullable = false)
    private String evidenceId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "wrapped_key", nullable = false)
    private byte[] wrappedKey;

    @Column(name = "wrapped_at", nullable = false)
    private Instant wrappedAt;

    protected EvidenceContentKey() {
        // required by JPA
    }

    public EvidenceContentKey(String evidenceId, UUID userId, byte[] wrappedKey, Instant wrappedAt) {
        this.evidenceId = evidenceId;
        this.userId = userId;
        this.wrappedKey = wrappedKey;
        this.wrappedAt = wrappedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public UUID getUserId() {
        return userId;
    }

    public byte[] getWrappedKey() {
        return wrappedKey;
    }

    public Instant getWrappedAt() {
        return wrappedAt;
    }
}

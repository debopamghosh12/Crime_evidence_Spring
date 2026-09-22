package com.blockevidence.backend.ledger;

import java.util.List;
import java.util.Optional;

/**
 * A2: resolves a user's OWN Fabric identity, so a write can be signed as that user rather than the single
 * shared application identity (design section 3.3, closes C-08 for the write path). Only {@link FabricLedgerService}
 * uses this (C-01); the interface is small and closed so a different enrollment mechanism (Option 2/3 in
 * A2_IDENTITY_DESIGN.md section 3.4 - in-app enrollment, or user-held keys) can replace the file-backed store
 * ({@link FileWalletIdentityStore}, Option 1) without FabricLedgerService changing at all.
 */
public interface IdentityStore {

    /** The credential for this user, or empty if none is enrolled (the caller then gets LEDGER_IDENTITY_MISSING). */
    Optional<WalletIdentity> find(String userId);

    /** User ids whose certificate expires within {@code days} days (design section 3.5). Feeds a health warning. */
    List<String> expiringWithin(int days);
}

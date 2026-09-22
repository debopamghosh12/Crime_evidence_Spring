package com.blockevidence.backend.ledger;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * A2: one user's own enrolled Fabric identity (certificate + private key). Never logged, never put in an error
 * body or any response (C-07); it exists only to sign the one write it was loaded for.
 */
public record WalletIdentity(X509Certificate certificate, PrivateKey privateKey) {
}

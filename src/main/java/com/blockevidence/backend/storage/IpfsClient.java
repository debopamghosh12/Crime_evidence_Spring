package com.blockevidence.backend.storage;

import java.io.InputStream;

/**
 * F1: the single seam to IPFS. Only classes in this package know it is IPFS (or a Pinata-style
 * service) behind the interface. Signatures are provisional and will be finalised with B2/F5 in
 * Phase 2.
 */
public interface IpfsClient {

    /** Pins the content and returns its CID. */
    String pin(InputStream content, String fileName);

    InputStream fetch(String cid);

    /** Compensation step for F5: removes a pin when the ledger write that should follow it fails. */
    void unpin(String cid);

    /** Feeds the G4 health check. Must not throw and must return within the configured timeout. */
    boolean isReachable();
}

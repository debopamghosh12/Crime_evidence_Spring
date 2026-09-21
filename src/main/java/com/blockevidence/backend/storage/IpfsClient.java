package com.blockevidence.backend.storage;

import java.io.InputStream;

/**
 * F1, FINAL for Phase 2. The single seam to IPFS: only classes in this package know it is IPFS (or a
 * Pinata-style service) behind the interface.
 *
 * <p>Design points that differ from the Phase 1 sketch:
 * <ul>
 * <li>{@link #read} takes a callback instead of returning an InputStream. The stream is only valid inside
 *     the callback and is always closed afterwards, so no caller can leak an open connection to the node.</li>
 * <li>Failures are split by cause: {@link ContentNotFoundException} (the node is fine, the content is not
 *     there) versus {@link StorageUnavailableException} (the node could not be used). Verification (C2)
 *     depends on the difference: missing content is a finding, an unreachable node is not.</li>
 * <li>{@link #pin} returns only the CID. Hash and byte count are computed by the caller while streaming
 *     (C1), because the node's reported size counts DAG overhead, not file bytes.</li>
 * </ul>
 */
public interface IpfsClient {

    /** Adds the content to the node, pins it, and returns its CID (CIDv1, base32). Consumes the stream. */
    String pin(InputStream content, String fileName);

    /**
     * Streams the content of {@code cid} to {@code reader}.
     *
     * @throws ContentNotFoundException    the node does not have the content
     * @throws StorageUnavailableException the node could not be reached or failed
     */
    <T> T read(String cid, ContentReader<T> reader);

    /**
     * Removes this node's pin so the content can be garbage-collected. Idempotent: unpinning something
     * that is not pinned succeeds. Used only to undo a pin whose ledger write failed (F5).
     */
    void unpin(String cid);

    /** Feeds the G4 health check. Must not throw and must return within the configured timeout. */
    boolean isReachable();
}

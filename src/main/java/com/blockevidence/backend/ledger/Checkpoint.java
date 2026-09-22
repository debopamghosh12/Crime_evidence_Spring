package com.blockevidence.backend.ledger;

/**
 * G3: a position in the chaincode event stream to resume from (design docs/G3_SYNC_DESIGN.md section 3). Java-only
 * (not the Fabric SDK's own {@code Checkpoint} type, which this is translated into inside {@code ledger/} only,
 * C-01). {@link #NONE} means "no prior position": the listener starts from block 0 and replays all history.
 */
public record Checkpoint(Long blockNumber, String txId) {

    public static final Checkpoint NONE = new Checkpoint(null, null);

    public boolean isNone() {
        return blockNumber == null;
    }
}

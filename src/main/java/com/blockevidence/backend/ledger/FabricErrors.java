package com.blockevidence.backend.ledger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns whatever the Fabric gateway reports into a {@link LedgerException}. The chaincode signals every
 * business failure as {@code CODE: message} (docs/CHAINCODE_DESIGN.md section 4), and the gateway wraps that
 * text inside its own exception messages; this finds the code by matching the closed set of known codes, so
 * no other text is ever interpreted. Kept free of gateway types so it can be unit-tested with plain strings.
 */
final class FabricErrors {

    private static final Pattern CHAINCODE_ERROR = Pattern.compile(
            "\\b(EVIDENCE_NOT_FOUND|EVIDENCE_EXISTS|FORBIDDEN_ROLE|INVALID_ARGUMENT|INVALID_STATE|VERSION_CONFLICT):\\s*"
                    + "([^\"\\\\\\n]*)");

    private FabricErrors() {
    }

    /**
     * @param text        all message text of the failure (exception message plus per-peer details)
     * @param transientIo true if the failure was a connectivity problem (gRPC UNAVAILABLE / DEADLINE_EXCEEDED)
     * @param mvccCommit  true if the transaction was ordered but rejected at commit as an MVCC read conflict
     */
    static LedgerException translate(String text, boolean transientIo, boolean mvccCommit) {
        Matcher m = CHAINCODE_ERROR.matcher(text == null ? "" : text);
        if (m.find()) {
            return new LedgerException(LedgerErrorCode.valueOf(m.group(1)), m.group(2).trim());
        }
        if (mvccCommit) {
            // Two writers touched the same key in the same block; the loser never committed, so nothing needs
            // re-reading (F5) - CONCURRENT_WRITE_CONFLICT, not VERSION_CONFLICT: this is safe to retry with the
            // exact same arguments, which a chaincode-reported stale expectedVersion is not.
            return new LedgerException(LedgerErrorCode.CONCURRENT_WRITE_CONFLICT,
                    "The transaction was invalidated by a concurrent write to the same record; retrying is safe");
        }
        if (transientIo) {
            return new LedgerException(LedgerErrorCode.LEDGER_UNAVAILABLE, "The ledger network is not reachable");
        }
        return new LedgerException(LedgerErrorCode.LEDGER_UNAVAILABLE, "The ledger rejected or failed the request");
    }
}

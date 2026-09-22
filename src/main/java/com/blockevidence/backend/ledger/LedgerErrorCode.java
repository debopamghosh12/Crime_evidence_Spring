package com.blockevidence.backend.ledger;

import org.springframework.http.HttpStatus;

/**
 * The closed set of ways a ledger operation can fail. The first six are exactly the codes the
 * chaincode returns as {@code CODE: message} (docs/CHAINCODE_DESIGN.md section 4); {@code LEDGER_UNAVAILABLE}
 * is raised by the Java side when the ledger cannot be reached; {@code LEDGER_IDENTITY_MISSING} (A2) is raised
 * by the Java side, before any chaincode call, when the acting user has no enrolled Fabric identity to sign
 * a write with. Each maps to one HTTP status, so callers never parse error text.
 *
 * <p>{@code CONCURRENT_WRITE_CONFLICT} (F5) is distinct from {@code VERSION_CONFLICT}: the chaincode itself
 * never returns this code (it has no concept of it) - it is raised only by {@code FabricErrors} when the
 * GATEWAY reports a transaction was endorsed but invalidated at commit as a Fabric-level MVCC read conflict
 * (two writers, same key, same block). That is retriable with the SAME arguments (nothing committed, so nothing
 * to re-read); a chaincode-reported {@code VERSION_CONFLICT} (the caller's {@code expectedVersion} is genuinely
 * stale) is not - retrying with the same arguments would just fail the same way again. Same HTTP status as
 * {@code VERSION_CONFLICT} (409) - this is an additive distinction for retry logic, not a new client-visible
 * behaviour for existing callers who only check status.
 */
public enum LedgerErrorCode {
    EVIDENCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    EVIDENCE_EXISTS(HttpStatus.CONFLICT),
    FORBIDDEN_ROLE(HttpStatus.FORBIDDEN),
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST),
    INVALID_STATE(HttpStatus.CONFLICT),
    VERSION_CONFLICT(HttpStatus.CONFLICT),
    CONCURRENT_WRITE_CONFLICT(HttpStatus.CONFLICT),
    LEDGER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    LEDGER_IDENTITY_MISSING(HttpStatus.FORBIDDEN);

    private final HttpStatus httpStatus;

    LedgerErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}

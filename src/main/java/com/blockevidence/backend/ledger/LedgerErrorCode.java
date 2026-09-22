package com.blockevidence.backend.ledger;

import org.springframework.http.HttpStatus;

/**
 * The closed set of ways a ledger operation can fail. The first six are exactly the codes the
 * chaincode returns as {@code CODE: message} (docs/CHAINCODE_DESIGN.md section 4); {@code LEDGER_UNAVAILABLE}
 * is raised by the Java side when the ledger cannot be reached; {@code LEDGER_IDENTITY_MISSING} (A2) is raised
 * by the Java side, before any chaincode call, when the acting user has no enrolled Fabric identity to sign
 * a write with. Each maps to one HTTP status, so callers never parse error text.
 */
public enum LedgerErrorCode {
    EVIDENCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    EVIDENCE_EXISTS(HttpStatus.CONFLICT),
    FORBIDDEN_ROLE(HttpStatus.FORBIDDEN),
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST),
    INVALID_STATE(HttpStatus.CONFLICT),
    VERSION_CONFLICT(HttpStatus.CONFLICT),
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

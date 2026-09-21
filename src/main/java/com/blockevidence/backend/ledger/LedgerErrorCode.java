package com.blockevidence.backend.ledger;

import org.springframework.http.HttpStatus;

/**
 * The closed set of ways a ledger operation can fail. The first six are exactly the codes the
 * chaincode returns as {@code CODE: message} (docs/CHAINCODE_DESIGN.md section 4); the last is raised
 * by the Java side when the ledger cannot be reached. Each maps to one HTTP status, so callers never
 * parse error text.
 */
public enum LedgerErrorCode {
    EVIDENCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    EVIDENCE_EXISTS(HttpStatus.CONFLICT),
    FORBIDDEN_ROLE(HttpStatus.FORBIDDEN),
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST),
    INVALID_STATE(HttpStatus.CONFLICT),
    VERSION_CONFLICT(HttpStatus.CONFLICT),
    LEDGER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus httpStatus;

    LedgerErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}

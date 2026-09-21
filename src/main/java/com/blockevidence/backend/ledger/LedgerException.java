package com.blockevidence.backend.ledger;

import com.blockevidence.backend.exception.ApiException;

/** A failed ledger operation. The HTTP status and API error code come from {@link LedgerErrorCode}. */
public class LedgerException extends ApiException {

    private final LedgerErrorCode ledgerCode;

    public LedgerException(LedgerErrorCode code, String message) {
        super(code.httpStatus(), code.name(), message);
        this.ledgerCode = code;
    }

    public LedgerErrorCode ledgerCode() {
        return ledgerCode;
    }
}

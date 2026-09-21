package com.blockevidence.backend.storage;

import com.blockevidence.backend.exception.FeatureNotImplementedException;

/** Thrown by HttpIpfsClient pin/fetch/unpin until file upload is built (Phase 2, B2). */
public class StorageNotImplementedException extends FeatureNotImplementedException {

    public StorageNotImplementedException(String operation) {
        super("Storage operation '" + operation + "' is not implemented yet");
    }
}

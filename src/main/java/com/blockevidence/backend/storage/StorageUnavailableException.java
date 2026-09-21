package com.blockevidence.backend.storage;

import com.blockevidence.backend.exception.ApiException;
import org.springframework.http.HttpStatus;

/** The IPFS node could not be reached, timed out, or returned an error. Maps to 503 STORAGE_UNAVAILABLE. */
public class StorageUnavailableException extends ApiException {

    public StorageUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", message);
    }
}

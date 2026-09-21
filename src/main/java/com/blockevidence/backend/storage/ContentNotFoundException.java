package com.blockevidence.backend.storage;

/**
 * The node is reachable but does not hold the content for this CID. Internal signal, deliberately not
 * an ApiException: what it means for the caller differs by case (C2 reports NOT_FOUND, B3 degrades to
 * "metadata unavailable"), so the service layer decides the HTTP outcome.
 */
public class ContentNotFoundException extends RuntimeException {

    public ContentNotFoundException(String cid) {
        super("No content available for CID " + cid);
    }
}

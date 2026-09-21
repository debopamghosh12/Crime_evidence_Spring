package com.blockevidence.backend.service;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * C1: SHA-256 as 64 lowercase hex characters, the form stored on the ledger and compared in C2.
 * Streaming variants never hold a whole file in memory.
 */
public final class Sha256 {

    private Sha256() {
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java spec", e);
        }
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    /** Reads the stream to its end and returns the hash; the caller owns and closes the stream. */
    public static String hex(InputStream in) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}

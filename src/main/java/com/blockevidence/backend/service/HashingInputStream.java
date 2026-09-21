package com.blockevidence.backend.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * C1: wraps an upload stream and computes its SHA-256 and byte count as the bytes flow through to IPFS,
 * so the file is read exactly once and never buffered. The hash is only complete after the stream has
 * been read to its end, which the IPFS upload does; call {@link #sha256Hex()} afterwards.
 */
public final class HashingInputStream extends FilterInputStream {

    private final MessageDigest digest = Sha256.newDigest();
    private long count;

    public HashingInputStream(InputStream in) {
        super(in);
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            digest.update((byte) b);
            count++;
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
        int n = super.read(buffer, off, len);
        if (n > 0) {
            digest.update(buffer, off, n);
            count += n;
        }
        return n;
    }

    // skip/mark would let bytes reach the consumer without passing through the digest.
    @Override
    public long skip(long n) throws IOException {
        byte[] discard = new byte[(int) Math.min(n, 8192)];
        int read = read(discard, 0, discard.length);
        return Math.max(read, 0);
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    public long byteCount() {
        return count;
    }

    /** Hash of everything read so far. Works on a copy, so calling it does not disturb the stream. */
    public String sha256Hex() {
        try {
            return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest());
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }
}

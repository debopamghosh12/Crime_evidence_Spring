package com.blockevidence.backend.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.blockevidence.backend.storage.ContentNotFoundException;
import com.blockevidence.backend.storage.ContentReader;
import com.blockevidence.backend.storage.IpfsClient;
import com.blockevidence.backend.storage.StorageUnavailableException;

/**
 * In-memory IpfsClient for service tests. Content-addressed like the real thing (the CID is derived from
 * the bytes, in a syntactically valid CIDv1 form), and able to misbehave on demand: {@link #corrupt}
 * swaps the bytes stored under a CID (a node whose disk was altered), {@link #lose} drops content, and
 * {@link #down} makes every call fail as an unreachable node would.
 */
public class FakeIpfsClient implements IpfsClient {

    public final Map<String, byte[]> store = new LinkedHashMap<>();
    public final Set<String> pins = new LinkedHashSet<>();
    public final List<String> unpinned = new ArrayList<>();
    public boolean down;

    @Override
    public String pin(InputStream content, String fileName) {
        checkUp();
        try {
            byte[] bytes = content.readAllBytes();
            String cid = cidOf(bytes);
            store.put(cid, bytes);
            pins.add(cid);
            return cid;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public <T> T read(String cid, ContentReader<T> reader) {
        checkUp();
        byte[] bytes = store.get(cid);
        if (bytes == null) {
            throw new ContentNotFoundException(cid);
        }
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return reader.read(in);
        } catch (IOException e) {
            throw new StorageUnavailableException("read failed");
        }
    }

    @Override
    public void unpin(String cid) {
        checkUp();
        pins.remove(cid);
        unpinned.add(cid);
    }

    @Override
    public boolean isReachable() {
        return !down;
    }

    /** Simulates tampering: the same CID now yields different bytes. */
    public void corrupt(String cid, byte[] newBytes) {
        store.put(cid, newBytes);
    }

    /** Simulates lost content: the node no longer has it. */
    public void lose(String cid) {
        store.remove(cid);
    }

    private void checkUp() {
        if (down) {
            throw new StorageUnavailableException("IPFS node not reachable");
        }
    }

    /** "b" + base32(sha256(bytes)): 53 lowercase characters, valid under Cid.isValid. */
    public static String cidOf(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
            StringBuilder out = new StringBuilder("b");
            int buffer = 0;
            int bits = 0;
            for (byte b : digest) {
                buffer = (buffer << 8) | (b & 0xff);
                bits += 8;
                while (bits >= 5) {
                    out.append(alphabet.charAt((buffer >> (bits - 5)) & 31));
                    bits -= 5;
                }
            }
            if (bits > 0) {
                out.append(alphabet.charAt((buffer << (5 - bits)) & 31));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

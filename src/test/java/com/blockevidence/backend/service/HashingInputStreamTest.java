package com.blockevidence.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** C1: the streaming hash must equal an independently computed one, however the stream is read. */
class HashingInputStreamTest {

    // Known-answer vector: SHA-256("abc"), from FIPS 180-4.
    static final String SHA256_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    static final String SHA256_EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void matchesTheKnownAnswerAndCountsBytes() throws IOException {
        try (HashingInputStream in = new HashingInputStream(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)))) {
            in.readAllBytes();
            assertThat(in.sha256Hex()).isEqualTo(SHA256_ABC);
            assertThat(in.byteCount()).isEqualTo(3);
        }
    }

    @Test
    void emptyStreamHasTheEmptyHash() throws IOException {
        try (HashingInputStream in = new HashingInputStream(new ByteArrayInputStream(new byte[0]))) {
            in.readAllBytes();
            assertThat(in.sha256Hex()).isEqualTo(SHA256_EMPTY);
            assertThat(in.byteCount()).isZero();
        }
    }

    @Test
    void singleByteReadsAndBulkReadsAgree() throws IOException {
        byte[] data = new byte[100_000];
        new java.util.Random(42).nextBytes(data);
        try (HashingInputStream bulk = new HashingInputStream(new ByteArrayInputStream(data));
                HashingInputStream single = new HashingInputStream(new ByteArrayInputStream(data))) {
            bulk.readAllBytes();
            while (single.read() != -1) {
                // consume one byte at a time
            }
            assertThat(bulk.sha256Hex()).isEqualTo(Sha256.hex(data)).isEqualTo(single.sha256Hex());
            assertThat(single.byteCount()).isEqualTo(100_000);
        }
    }

    @Test
    void skippedBytesStillCountTowardsTheHash() throws IOException {
        try (HashingInputStream in = new HashingInputStream(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)))) {
            in.skip(3);
            assertThat(in.sha256Hex()).isEqualTo(SHA256_ABC);
        }
    }

    @Test
    void readingTheHashMidStreamDoesNotDisturbTheFinalHash() throws IOException {
        try (HashingInputStream in = new HashingInputStream(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)))) {
            in.read();
            in.sha256Hex();
            in.readAllBytes();
            assertThat(in.sha256Hex()).isEqualTo(SHA256_ABC);
        }
    }
}

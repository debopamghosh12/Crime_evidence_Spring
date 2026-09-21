package com.blockevidence.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.blockevidence.backend.config.IpfsProperties;
import com.blockevidence.backend.support.FakeIpfsClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs HttpIpfsClient against a fake node built on the JDK's own HttpServer (no test dependency). The
 * fake reproduces the behaviours measured on a real Kubo 0.43 node: POST-only API, add answers one JSON
 * line, cat of unknown content answers 500 "not found locally", pin/rm of an unpinned CID answers 500
 * "not pinned".
 */
class HttpIpfsClientTest {

    static final byte[] CONTENT = "evidence bytes".getBytes(StandardCharsets.UTF_8);
    static final String KNOWN_CID = FakeIpfsClient.cidOf(CONTENT);
    static final String UNKNOWN_CID = FakeIpfsClient.cidOf("never stored".getBytes(StandardCharsets.UTF_8));

    HttpServer server;
    final AtomicReference<String> lastRequestLine = new AtomicReference<>();
    final AtomicReference<String> lastContentType = new AtomicReference<>();
    final AtomicReference<byte[]> lastBody = new AtomicReference<>();
    final AtomicInteger pinRmCalls = new AtomicInteger();
    volatile long delayMillis;
    volatile int versionStatus = 200;

    String startFakeNode() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v0/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            lastRequestLine.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            lastBody.set(exchange.getRequestBody().readAllBytes());
            sleep(delayMillis);
            if (!"POST".equals(exchange.getRequestMethod())) {
                reply(exchange, 405, "{\"Message\":\"POST only\"}");
            } else if (path.endsWith("/version")) {
                reply(exchange, versionStatus, "{\"Version\":\"0.43.1\"}");
            } else if (path.endsWith("/add")) {
                reply(exchange, 200, "{\"Name\":\"probe.txt\",\"Hash\":\"" + KNOWN_CID + "\",\"Size\":\"14\"}\n");
            } else if (path.endsWith("/cat")) {
                String query = exchange.getRequestURI().getQuery();
                if (query.contains("arg=" + KNOWN_CID)) {
                    reply(exchange, 200, new String(CONTENT, StandardCharsets.UTF_8));
                } else if (query.contains("arg=" + FakeIpfsClient.cidOf("boom".getBytes()))) {
                    reply(exchange, 500, "{\"Message\":\"datastore exploded\",\"Code\":0,\"Type\":\"error\"}");
                } else {
                    reply(exchange, 500, "{\"Message\":\"block was not found locally (offline): ipld: could not find "
                            + UNKNOWN_CID + "\",\"Code\":0,\"Type\":\"error\"}");
                }
            } else if (path.endsWith("/pin/rm")) {
                if (pinRmCalls.incrementAndGet() == 1) {
                    reply(exchange, 200, "{\"Pins\":[\"" + KNOWN_CID + "\"]}");
                } else {
                    reply(exchange, 500, "{\"Message\":\"not pinned or pinned indirectly\",\"Code\":0,\"Type\":\"error\"}");
                }
            } else {
                reply(exchange, 404, "nope");
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    HttpIpfsClient client(String url, Duration probeTimeout) {
        return new HttpIpfsClient(new IpfsProperties(url, probeTimeout, Duration.ofSeconds(5), Duration.ofSeconds(2)),
                JsonMapper.builder().build());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------------------- probe (G4)

    @Test
    void reachableWhenNodeAnswersThePostVersionCall() throws IOException {
        String url = startFakeNode();

        assertThat(client(url, Duration.ofSeconds(2)).isReachable()).isTrue();
        assertThat(lastRequestLine.get()).isEqualTo("POST /api/v0/version");
    }

    @Test
    void notReachableWhenNodeAnswersWithAnErrorStatus() throws IOException {
        String url = startFakeNode();
        versionStatus = 500;

        assertThat(client(url, Duration.ofSeconds(2)).isReachable()).isFalse();
    }

    @Test
    void notReachableWhenNothingListens() throws IOException {
        String url = startFakeNode();
        server.stop(0);

        assertThat(client(url, Duration.ofSeconds(2)).isReachable()).isFalse();
    }

    @Test
    void slowNodeIsReportedUnreachableWithinTheProbeTimeoutInsteadOfHanging() throws IOException {
        String url = startFakeNode();
        delayMillis = 3000;
        HttpIpfsClient client = client(url, Duration.ofMillis(300));

        long start = System.nanoTime();
        boolean reachable = client.isReachable();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(reachable).isFalse();
        assertThat(elapsedMillis).isLessThan(2000);
    }

    // ------------------------------------------------------------------------------------ pin

    @Test
    void pinUploadsMultipartWithTheFilenameAndReturnsTheNodesCid() throws IOException {
        String url = startFakeNode();

        String cid = client(url, Duration.ofSeconds(2)).pin(new ByteArrayInputStream(CONTENT), "probe.txt");

        assertThat(cid).isEqualTo(KNOWN_CID);
        assertThat(lastRequestLine.get()).startsWith("POST /api/v0/add?").contains("cid-version=1").contains("pin=true");
        assertThat(lastContentType.get()).startsWith("multipart/form-data");
        String body = new String(lastBody.get(), StandardCharsets.ISO_8859_1);
        assertThat(body).contains("name=\"file\"").contains("filename=\"probe.txt\"").contains("evidence bytes");
    }

    @Test
    void pinFailsAsStorageUnavailableWhenTheNodeIsDown() throws IOException {
        String url = startFakeNode();
        server.stop(0);

        assertThatThrownBy(() -> client(url, Duration.ofSeconds(2)).pin(new ByteArrayInputStream(CONTENT), "x"))
                .isInstanceOf(StorageUnavailableException.class);
    }

    // ------------------------------------------------------------------------------------ read

    @Test
    void readStreamsTheContentToTheCallback() throws IOException {
        String url = startFakeNode();

        String text = client(url, Duration.ofSeconds(2)).read(KNOWN_CID,
                in -> new String(in.readAllBytes(), StandardCharsets.UTF_8));

        assertThat(text).isEqualTo("evidence bytes");
        assertThat(lastRequestLine.get()).startsWith("POST /api/v0/cat?arg=" + KNOWN_CID).contains("timeout=2s");
    }

    @Test
    void readOfContentTheNodeLacksIsNotFoundNotUnavailable() throws IOException {
        String url = startFakeNode();

        assertThatThrownBy(() -> client(url, Duration.ofSeconds(2)).read(UNKNOWN_CID, in -> in.readAllBytes()))
                .isInstanceOf(ContentNotFoundException.class);
    }

    @Test
    void readWhenTheNodeFailsForAnotherReasonIsUnavailable() throws IOException {
        String url = startFakeNode();
        String boom = FakeIpfsClient.cidOf("boom".getBytes());

        assertThatThrownBy(() -> client(url, Duration.ofSeconds(2)).read(boom, in -> in.readAllBytes()))
                .isInstanceOf(StorageUnavailableException.class);
    }

    @Test
    void readWhenTheNodeIsDownIsUnavailable() throws IOException {
        String url = startFakeNode();
        server.stop(0);

        assertThatThrownBy(() -> client(url, Duration.ofSeconds(2)).read(KNOWN_CID, in -> in.readAllBytes()))
                .isInstanceOf(StorageUnavailableException.class);
    }

    @Test
    void aMalformedCidNeverReachesTheNode() throws IOException {
        String url = startFakeNode();

        assertThatThrownBy(() -> client(url, Duration.ofSeconds(2)).read("x&arg=../../etc", in -> in.readAllBytes()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(lastRequestLine.get()).as("no request was made").isNull();
    }

    // ---------------------------------------------------------------------------------- unpin

    @Test
    void unpinIsIdempotent() throws IOException {
        String url = startFakeNode();
        HttpIpfsClient client = client(url, Duration.ofSeconds(2));

        client.unpin(KNOWN_CID);   // 200
        client.unpin(KNOWN_CID);   // 500 "not pinned": must be treated as success

        assertThat(pinRmCalls.get()).isEqualTo(2);
    }
}

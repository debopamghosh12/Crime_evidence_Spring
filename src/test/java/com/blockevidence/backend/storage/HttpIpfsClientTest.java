package com.blockevidence.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.blockevidence.backend.config.IpfsProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Runs HttpIpfsClient against a fake node built on the JDK's own HttpServer (no test dependency). */
class HttpIpfsClientTest {

    HttpServer server;

    AtomicReference<String> lastRequest = new AtomicReference<>();

    String startFakeNode(int statusForPost, long delayMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v0/version", exchange -> {
            lastRequest.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            // Behave like Kubo: the RPC API answers only POST, a GET gets 405.
            int status = "POST".equals(exchange.getRequestMethod()) ? statusForPost : 405;
            byte[] body = "{\"Version\":\"0.30.0\"}".getBytes();
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    HttpIpfsClient client(String url, Duration timeout) {
        return new HttpIpfsClient(new IpfsProperties(url, timeout));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void reachableWhenNodeAnswersThePostVersionCall() throws IOException {
        String url = startFakeNode(200, 0);

        assertThat(client(url, Duration.ofSeconds(2)).isReachable()).isTrue();
        assertThat(lastRequest.get()).isEqualTo("POST /api/v0/version");
    }

    @Test
    void notReachableWhenNodeAnswersWithAnErrorStatus() throws IOException {
        String url = startFakeNode(500, 0);

        assertThat(client(url, Duration.ofSeconds(2)).isReachable()).isFalse();
    }

    @Test
    void notReachableWhenNothingListens() throws IOException {
        String url = startFakeNode(200, 0);
        server.stop(0); // free the port, then point the client at it

        assertThat(client(url, Duration.ofSeconds(2)).isReachable()).isFalse();
    }

    @Test
    void slowNodeIsReportedUnreachableWithinTheTimeoutInsteadOfHanging() throws IOException {
        String url = startFakeNode(200, 3000);
        HttpIpfsClient client = client(url, Duration.ofMillis(300));

        long start = System.nanoTime();
        boolean reachable = client.isReachable();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(reachable).isFalse();
        assertThat(elapsedMillis).isLessThan(2000);
    }

    @Test
    void pinFetchAndUnpinAreStubsInPhase1() {
        HttpIpfsClient client = client("http://127.0.0.1:1", Duration.ofSeconds(1));

        assertThatThrownBy(() -> client.pin(new ByteArrayInputStream(new byte[0]), "f")).isInstanceOf(StorageNotImplementedException.class);
        assertThatThrownBy(() -> client.fetch("Qm")).isInstanceOf(StorageNotImplementedException.class);
        assertThatThrownBy(() -> client.unpin("Qm")).isInstanceOf(StorageNotImplementedException.class);
    }
}

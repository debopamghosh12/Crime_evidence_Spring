package com.blockevidence.backend.storage;

import java.io.InputStream;

import com.blockevidence.backend.config.IpfsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * F1 (partial). Talks to a Kubo (go-ipfs) node's RPC API over HTTP. In Phase 1 only
 * {@link #isReachable()} is real; pin/fetch/unpin are stubs that throw until B2 (file upload).
 *
 * <p>Built on RestClient (already part of spring-web) so no extra HTTP library is needed.
 */
@Component
public class HttpIpfsClient implements IpfsClient {

    private static final Logger log = LoggerFactory.getLogger(HttpIpfsClient.class);

    private final RestClient restClient;
    private final String apiUrl;

    public HttpIpfsClient(IpfsProperties properties) {
        this.apiUrl = properties.apiUrl();
        // Explicit timeouts: with the JDK default (none) a hung node would hang the health probe too.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        this.restClient = RestClient.builder().baseUrl(properties.apiUrl()).requestFactory(requestFactory).build();
    }

    @Override
    public String pin(InputStream content, String fileName) {
        throw new StorageNotImplementedException("pin");
    }

    @Override
    public InputStream fetch(String cid) {
        throw new StorageNotImplementedException("fetch");
    }

    @Override
    public void unpin(String cid) {
        throw new StorageNotImplementedException("unpin");
    }

    /**
     * Kubo's RPC API accepts POST only (a GET is answered 405), so the liveness probe is a POST to
     * /api/v0/version. Any 2xx counts as reachable; a connection failure, timeout or error status
     * means not.
     */
    @Override
    public boolean isReachable() {
        try {
            restClient.post().uri("/api/v0/version").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.warn("IPFS node not reachable at {}: {}", apiUrl, e.getMessage());
            return false;
        }
    }
}

package com.blockevidence.backend.storage;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.blockevidence.backend.config.IpfsProperties;
import com.blockevidence.backend.domain.Cid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F1. Talks to a Kubo (go-ipfs) node's RPC API. Behaviour below was verified against a real Kubo
 * 0.43 node, not assumed:
 * <ul>
 * <li>The RPC API accepts POST only.</li>
 * <li>{@code add} answers one JSON line, e.g. {"Name":"x","Hash":"bafk...","Size":"24"}.</li>
 * <li>{@code cat} of content the node lacks answers HTTP 500 with a message such as "block was not found
 *     locally"; that is how "not found" is recognised, since Kubo has no 404 for it.</li>
 * <li>{@code pin/rm} of something not pinned answers HTTP 500 "not pinned", which unpin() treats as success.</li>
 * </ul>
 * Two RestClients share one node: a probe client with the short health timeout, and a transfer client
 * with the long one, so a slow upload can never make the health check look slow.
 */
@Component
public class HttpIpfsClient implements IpfsClient {

    private static final Logger log = LoggerFactory.getLogger(HttpIpfsClient.class);

    private final RestClient probeClient;
    private final RestClient transferClient;
    private final JsonMapper jsonMapper;
    private final String apiUrl;
    private final long lookupSeconds;

    public HttpIpfsClient(IpfsProperties properties, JsonMapper jsonMapper) {
        this.apiUrl = properties.apiUrl();
        this.jsonMapper = jsonMapper;
        this.lookupSeconds = Math.max(1, properties.lookupTimeout().toSeconds());
        this.probeClient = buildClient(properties, properties.timeout());
        this.transferClient = buildClient(properties, properties.transferTimeout());
    }

    private static RestClient buildClient(IpfsProperties properties, java.time.Duration timeout) {
        // Explicit timeouts: with the JDK default (none) a hung node would hang the caller too.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(properties.apiUrl()).requestFactory(factory).build();
    }

    @Override
    public String pin(InputStream content, String fileName) {
        // The filename only labels the node's directory entry; the original name is kept in the metadata
        // document. Non-ASCII is replaced so the multipart header is portable across servers.
        String name = (fileName == null || fileName.isBlank() ? "content" : fileName).replaceAll("[^\\x20-\\x7E]", "_");
        // Built from plain Spring types on purpose: MultipartBodyBuilder references reactive-streams
        // classes that are absent from this servlet-only classpath and fails at runtime with NoClassDefFoundError.
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        partHeaders.setContentDispositionFormData("file", name);
        // Deliberately a plain InputStreamResource, not a subclass: Spring only skips a full read to
        // learn the Content-Length when the class is exactly InputStreamResource, so a subclass here
        // would consume the whole stream before the upload starts.
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new HttpEntity<>(new InputStreamResource(content), partHeaders));
        try {
            String response = transferClient.post()
                    .uri("/api/v0/add?cid-version=1&raw-leaves=true&progress=false&pin=true")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(String.class);
            return parseCid(response);
        } catch (ResourceAccessException e) {
            throw new StorageUnavailableException("IPFS node not reachable while storing content");
        } catch (RestClientException e) {
            log.warn("IPFS add failed: {}", e.getMessage());
            throw new StorageUnavailableException("IPFS node failed to store the content");
        }
    }

    private String parseCid(String response) {
        try {
            if (response == null || response.isBlank()) {
                throw new StorageUnavailableException("IPFS node returned an empty answer to add");
            }
            // One JSON object per line; a single file yields one line. Use the last non-empty one.
            String[] lines = response.strip().split("\\R");
            JsonNode node = jsonMapper.readTree(lines[lines.length - 1]);
            String cid = node.path("Hash").asString("");
            if (!Cid.isValid(cid)) {
                throw new StorageUnavailableException("IPFS node returned an unexpected CID");
            }
            return cid;
        } catch (JacksonException e) {
            throw new StorageUnavailableException("IPFS node returned an unreadable answer to add");
        }
    }

    @Override
    public <T> T read(String cid, ContentReader<T> reader) {
        requireValidCid(cid);
        try {
            return transferClient.post()
                    .uri("/api/v0/cat?arg={cid}&timeout={t}s", cid, lookupSeconds)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return reader.read(response.getBody());
                        }
                        String message = new String(response.getBody().readNBytes(2048), StandardCharsets.UTF_8);
                        if (indicatesMissing(message)) {
                            throw new ContentNotFoundException(cid);
                        }
                        log.warn("IPFS cat failed with HTTP {}: {}", response.getStatusCode().value(), message);
                        throw new StorageUnavailableException("IPFS node failed to read the content");
                    });
        } catch (ResourceAccessException e) {
            // Includes an I/O failure in the middle of the stream: the content could not be read fully.
            throw new StorageUnavailableException("IPFS node not reachable while reading content");
        }
    }

    private static boolean indicatesMissing(String message) {
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("not found") || m.contains("could not find") || m.contains("deadline exceeded")
                || m.contains("context canceled");
    }

    @Override
    public void unpin(String cid) {
        requireValidCid(cid);
        try {
            transferClient.post().uri("/api/v0/pin/rm?arg={cid}", cid).exchange((request, response) -> {
                if (response.getStatusCode().is2xxSuccessful()) {
                    return null;
                }
                String message = new String(response.getBody().readNBytes(2048), StandardCharsets.UTF_8);
                if (message.toLowerCase(java.util.Locale.ROOT).contains("not pinned")) {
                    return null; // already unpinned: the goal state
                }
                log.warn("IPFS pin/rm failed with HTTP {}: {}", response.getStatusCode().value(), message);
                throw new StorageUnavailableException("IPFS node failed to unpin the content");
            });
        } catch (ResourceAccessException e) {
            throw new StorageUnavailableException("IPFS node not reachable while unpinning content");
        }
    }

    /**
     * Kubo's RPC API accepts POST only (a GET is answered 405), so the liveness probe is a POST to
     * /api/v0/version. Any 2xx counts as reachable; a connection failure, timeout or error status
     * means not.
     */
    @Override
    public boolean isReachable() {
        try {
            probeClient.post().uri("/api/v0/version").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.warn("IPFS node not reachable at {}: {}", apiUrl, e.getMessage());
            return false;
        }
    }

    private static void requireValidCid(String cid) {
        // Keeps a malformed value from ever reaching the node's query string.
        if (!Cid.isValid(cid)) {
            throw new IllegalArgumentException("Not a valid CID");
        }
    }
}

package com.blockevidence.backend.ledger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.blockevidence.backend.config.FabricProperties;
import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.security.Role;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.TlsChannelCredentials;
import org.hyperledger.fabric.client.CallOption;
import org.hyperledger.fabric.client.CommitException;
import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.GatewayException;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * G1/G2: the Hyperledger Fabric implementation of LedgerService, talking to the {@code evidence} chaincode
 * (docs/CHAINCODE_DESIGN.md) through the Fabric Gateway on an Org1 peer. This is the ONLY class that may import
 * Fabric types (constraint C-01). Not active under the {@code memory-ledger} profile.
 *
 * <p>Writes use {@code submitTransaction}: endorsed by the peers the gateway selects (both orgs, per the
 * channel's default policy), ordered, and this call returns only after the transaction is COMMITTED, so a
 * successful return means the write is on the ledger. Reads use {@code evaluateTransaction}, answered by one
 * peer without ordering.
 *
 * <p>The connection is created lazily on first use, so the application starts without Fabric; a missing
 * identity configuration or an unreachable network then surfaces as 503 LEDGER_UNAVAILABLE on the call, and
 * as UNKNOWN/DOWN in health, never as a startup failure.
 *
 * <p><b>A2 (design docs/A2_IDENTITY_DESIGN.md section 3.3):</b> reads and the health probe use the SERVICE
 * identity (unchanged, {@code FABRIC_CERT_PATH}/{@code FABRIC_KEY_PATH}); every WRITE is signed with the
 * ACTING USER's own identity, loaded from {@link IdentityStore}. A user with no wallet entry gets
 * {@code 403 LEDGER_IDENTITY_MISSING} before any chaincode call is attempted - never a silent fallback to the
 * service identity, which would reopen constraint C-08. One shared gRPC {@link ManagedChannel} (transport only)
 * carries both the service {@link Gateway} and a small bounded cache of per-user {@link Gateway}s (each is
 * lightweight: it wraps the shared channel with a signing identity, not a new connection).
 */
@Service
@Profile("!memory-ledger")
public class FabricLedgerService implements LedgerService, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FabricLedgerService.class);

    /** A well-formed id that cannot exist; asking for it proves the chaincode is answering (health check). */
    private static final String PROBE_ID = "EV-00000000-0000-4000-8000-000000000000";

    /** Bounded so a long-running backend cannot accumulate one Gateway per distinct user forever. Generous for a
     *  project this size (FEATURE_LIST has 6 dev roles; real deployments would size this to active users). */
    private static final int MAX_CACHED_USER_GATEWAYS = 64;

    private final FabricProperties properties;
    private final IdentityStore identityStore;
    private final JsonMapper json;

    private ManagedChannel channel;
    private Gateway serviceGateway;
    private Contract serviceContract;

    /** LRU by access order; {@link #trimUserGateways()} closes and drops the eldest entry past the cap. */
    private final Map<String, UserConnection> userGateways = new LinkedHashMap<>(16, 0.75f, true);

    private record UserConnection(Gateway gateway, Contract contract) {
    }

    public FabricLedgerService(FabricProperties properties, IdentityStore identityStore, JsonMapper jsonMapper) {
        this.properties = properties;
        this.identityStore = identityStore;
        // The chaincode adds fields the Java records do not model (docType); ignore them rather than fail.
        this.json = jsonMapper.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    // ------------------------------------------------------------------------------------- writes

    @Override
    public LedgerTxResult createEvidence(LedgerNewEvidence e, LedgerActor actor) {
        return submit(actor, "CreateEvidence", e.evidenceId(), e.caseId(), e.evidenceType().name(), e.metadataCid(),
                e.metadataSha256(), nullToEmpty(e.fileCid()), nullToEmpty(e.fileSha256()),
                e.fileSize() == null ? "" : e.fileSize().toString(), actor.userId(), actor.role().name());
    }

    @Override
    public LedgerTxResult updateEvidence(String evidenceId, int expectedVersion, String newMetadataCid,
            String newMetadataSha256, String reason, LedgerActor actor) {
        return submit(actor, "UpdateEvidence", evidenceId, Integer.toString(expectedVersion), newMetadataCid,
                newMetadataSha256, reason, actor.userId(), actor.role().name());
    }

    @Override
    public LedgerTxResult updateStatus(String evidenceId, int expectedVersion, EvidenceStatus newStatus,
            String reason, LedgerActor actor) {
        return submit(actor, "UpdateStatus", evidenceId, Integer.toString(expectedVersion), newStatus.name(), reason,
                actor.userId(), actor.role().name());
    }

    @Override
    public LedgerTxResult requestDisposal(String evidenceId, int expectedVersion, String reason, LedgerActor actor) {
        return submit(actor, "RequestDisposal", evidenceId, Integer.toString(expectedVersion), reason, actor.userId(),
                actor.role().name());
    }

    @Override
    public LedgerTxResult approveDisposal(String evidenceId, int expectedVersion, String note, LedgerActor actor) {
        return submit(actor, "ApproveDisposal", evidenceId, Integer.toString(expectedVersion), note, actor.userId(),
                actor.role().name());
    }

    @Override
    public LedgerTxResult rejectDisposal(String evidenceId, int expectedVersion, String note, LedgerActor actor) {
        return submit(actor, "RejectDisposal", evidenceId, Integer.toString(expectedVersion), note, actor.userId(),
                actor.role().name());
    }

    @Override
    public LedgerTxResult initiateTransfer(String evidenceId, int expectedVersion, String toUserId, Role toRole,
            String reason, String notes, LedgerActor actor) {
        return submit(actor, "InitiateTransfer", evidenceId, Integer.toString(expectedVersion), toUserId, toRole.name(), reason,
                nullToEmpty(notes), actor.userId(), actor.role().name());
    }

    @Override
    public LedgerTxResult acceptTransfer(String evidenceId, int expectedVersion, String note, LedgerActor actor) {
        return submit(actor, "AcceptTransfer", evidenceId, Integer.toString(expectedVersion), nullToEmpty(note), actor.userId(),
                actor.role().name());
    }

    @Override
    public LedgerTxResult rejectTransfer(String evidenceId, int expectedVersion, String note, LedgerActor actor) {
        return submit(actor, "RejectTransfer", evidenceId, Integer.toString(expectedVersion), nullToEmpty(note), actor.userId(),
                actor.role().name());
    }

    @Override
    public LedgerTxResult cancelTransfer(String evidenceId, int expectedVersion, String note, LedgerActor actor) {
        return submit(actor, "CancelTransfer", evidenceId, Integer.toString(expectedVersion), nullToEmpty(note), actor.userId(),
                actor.role().name());
    }

    @Override
    public List<String> findPendingTransferIds(String userId) {
        return parse(evaluate("FindPendingTransfers", userId), new TypeReference<List<String>>() {
        });
    }

    // -------------------------------------------------------------------------------------- reads

    @Override
    public Optional<LedgerEvidenceRecord> getEvidence(String evidenceId) {
        try {
            return Optional.of(parse(evaluate("GetEvidence", evidenceId), LedgerEvidenceRecord.class));
        } catch (LedgerException e) {
            if (e.ledgerCode() == LedgerErrorCode.EVIDENCE_NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public List<LedgerHistoryEntry> getHistory(String evidenceId) {
        return parse(evaluate("GetHistory", evidenceId), new TypeReference<List<LedgerHistoryEntry>>() {
        });
    }

    @Override
    public List<String> findEvidenceIdsByCid(String cid) {
        return parse(evaluate("FindByCid", cid), new TypeReference<List<String>>() {
        });
    }

    @Override
    public LedgerHealth health() {
        if (!properties.isConfigured()) {
            return new LedgerHealth(LedgerHealth.State.UNKNOWN, "Fabric identity not configured (set FABRIC_TLS_CERT_PATH, "
                    + "FABRIC_CERT_PATH, FABRIC_KEY_PATH); channel=" + properties.channel()
                    + " chaincode=" + properties.chaincode());
        }
        try {
            evaluate("GetEvidence", PROBE_ID);
            return up();
        } catch (LedgerException e) {
            // "not found" for the probe id is the healthy answer: it came from the chaincode itself.
            if (e.ledgerCode() == LedgerErrorCode.EVIDENCE_NOT_FOUND) {
                return up();
            }
            return new LedgerHealth(LedgerHealth.State.DOWN, e.getMessage());
        }
    }

    private LedgerHealth up() {
        return new LedgerHealth(LedgerHealth.State.UP, "chaincode " + properties.chaincode() + " answering on channel "
                + properties.channel() + " via " + properties.peerEndpoint() + " as " + properties.mspId());
    }

    // ---------------------------------------------------------------------------------- plumbing

    /** A2: signed with the ACTOR's own identity (LEDGER_IDENTITY_MISSING if they have none), not the service identity. */
    private LedgerTxResult submit(LedgerActor actor, String function, String... args) {
        try {
            byte[] result = contractFor(actor).submitTransaction(function, args);
            WireTxResult tx = parse(result, WireTxResult.class);
            return new LedgerTxResult(tx.txId(), tx.timestamp());
        } catch (GatewayException | CommitException e) {
            throw translate(e);
        }
    }

    /** Reads need no per-user identity (C-08 does not apply to them): always the service identity. */
    private byte[] evaluate(String function, String... args) {
        try {
            return serviceContract().evaluateTransaction(function, args);
        } catch (GatewayException e) {
            throw translate(e);
        }
    }

    /**
     * Flattens the exception, the per-peer error details and the causes into one string for FabricErrors.
     * Fragments are joined with NEWLINES, not spaces: FabricErrors reads a chaincode message up to the end of
     * its line, so a space would let it run on into the gateway's next fragment ("... ABORTED: failed to
     * endorse transaction ..."), which a live run against real Fabric showed.
     */
    private static LedgerException translate(Exception e) {
        StringBuilder text = new StringBuilder(String.valueOf(e.getMessage()));
        boolean io = false;
        boolean mvcc = false;
        if (e instanceof GatewayException ge) {
            ge.getDetails().forEach(d -> text.append('\n').append(d.getMessage()));
            Status.Code code = ge.getStatus().getCode();
            io = code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
        }
        if (e instanceof CommitException ce) {
            mvcc = ce.getCode().name().contains("MVCC");
            text.append('\n').append(ce.getCode().name());
        }
        for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
            text.append('\n').append(c.getMessage());
        }
        return FabricErrors.translate(text.toString(), io, mvcc);
    }

    // package-private: the wire-format tests feed it real chaincode output
    <T> T parse(byte[] bytes, Class<T> type) {
        try {
            return json.readValue(bytes, type);
        } catch (JacksonException e) {
            throw new LedgerException(LedgerErrorCode.LEDGER_UNAVAILABLE, "The ledger returned an unreadable answer");
        }
    }

    <T> T parse(byte[] bytes, TypeReference<T> type) {
        try {
            return json.readValue(bytes, type);
        } catch (JacksonException e) {
            throw new LedgerException(LedgerErrorCode.LEDGER_UNAVAILABLE, "The ledger returned an unreadable answer");
        }
    }

    /** {@code txId} and {@code timestamp} (ledger time) of a committed write; matches the chaincode's TxResult. */
    record WireTxResult(String txId, Instant timestamp, int version) {
    }

    private synchronized Contract serviceContract() {
        if (serviceContract != null) {
            return serviceContract;
        }
        if (!properties.isConfigured()) {
            throw new LedgerException(LedgerErrorCode.LEDGER_UNAVAILABLE,
                    "The Fabric identity is not configured (FABRIC_TLS_CERT_PATH, FABRIC_CERT_PATH, FABRIC_KEY_PATH)");
        }
        try {
            X509Certificate certificate = readCertificate(resolveFile(properties.certPath()));
            PrivateKey privateKey = readPrivateKey(resolveFile(properties.keyPath()));
            Gateway newGateway = Gateway.newInstance()
                    .identity(new X509Identity(properties.mspId(), certificate))
                    .signer(Signers.newPrivateKeySigner(privateKey))
                    .connection(channel())
                    .evaluateOptions(deadline(properties.evaluateTimeout()))
                    .endorseOptions(deadline(properties.endorseTimeout()))
                    .submitOptions(deadline(properties.submitTimeout()))
                    .commitStatusOptions(deadline(properties.commitTimeout()))
                    .connect();
            this.serviceGateway = newGateway;
            this.serviceContract = newGateway.getNetwork(properties.channel()).getContract(properties.chaincode());
            log.info("Connected to Fabric peer {} (channel {}, chaincode {}, msp {}, service identity)",
                    properties.peerEndpoint(), properties.channel(), properties.chaincode(), properties.mspId());
            return serviceContract;
        } catch (IOException | CertificateException | InvalidKeyException | RuntimeException e) {
            // Deliberately not logging the paths' contents; the exception type and message suffice.
            log.warn("Cannot connect to Fabric (service identity): {}", e.toString());
            throw new LedgerException(LedgerErrorCode.LEDGER_UNAVAILABLE, "Cannot set up the Fabric connection");
        }
    }

    /** A2: the acting user's own Gateway/Contract, from a small bounded cache, built on the SHARED channel. */
    private synchronized Contract contractFor(LedgerActor actor) {
        UserConnection existing = userGateways.get(actor.userId());
        if (existing != null) {
            return existing.contract();
        }
        WalletIdentity identity = identityStore.find(actor.userId()).orElseThrow(() -> new LedgerException(
                LedgerErrorCode.LEDGER_IDENTITY_MISSING, "Your account has no ledger identity; ask an administrator to enroll it"));
        try {
            Gateway userGateway = Gateway.newInstance()
                    .identity(new X509Identity(properties.mspId(), identity.certificate()))
                    .signer(Signers.newPrivateKeySigner(identity.privateKey()))
                    .connection(channel())
                    .evaluateOptions(deadline(properties.evaluateTimeout()))
                    .endorseOptions(deadline(properties.endorseTimeout()))
                    .submitOptions(deadline(properties.submitTimeout()))
                    .commitStatusOptions(deadline(properties.commitTimeout()))
                    .connect();
            Contract userContract = userGateway.getNetwork(properties.channel()).getContract(properties.chaincode());
            userGateways.put(actor.userId(), new UserConnection(userGateway, userContract));
            trimUserGateways();
            return userContract;
        } catch (RuntimeException e) {
            log.warn("Cannot set up a per-user Fabric connection: {}", e.toString());
            throw new LedgerException(LedgerErrorCode.LEDGER_UNAVAILABLE, "Cannot set up the Fabric connection for this user");
        }
    }

    /** Closes and drops the least-recently-used entry once the cache is over its cap (called with the lock held). */
    private void trimUserGateways() {
        if (userGateways.size() <= MAX_CACHED_USER_GATEWAYS) {
            return;
        }
        var it = userGateways.entrySet().iterator();
        if (it.hasNext()) {
            it.next().getValue().gateway().close();
            it.remove();
        }
    }

    /** Lazily creates the ONE shared gRPC transport; both the service Gateway and every per-user Gateway reuse it. */
    private synchronized ManagedChannel channel() {
        if (channel != null) {
            return channel;
        }
        try {
            channel = Grpc.newChannelBuilder(properties.peerEndpoint(),
                            TlsChannelCredentials.newBuilder().trustManager(resolveFile(properties.tlsCertPath()).toFile()).build())
                    .overrideAuthority(properties.peerHostAlias())
                    .build();
            return channel;
        } catch (IOException e) {
            log.warn("Cannot read the Fabric TLS trust material: {}", e.toString());
            throw new LedgerException(LedgerErrorCode.LEDGER_UNAVAILABLE, "Cannot set up the Fabric connection");
        }
    }

    private static CallOption deadline(java.time.Duration d) {
        return CallOption.deadlineAfter(d.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** A path to a file, or to a directory containing exactly one file (Fabric's signcerts/ and keystore/). */
    static Path resolveFile(String configured) throws IOException {
        Path path = Path.of(configured);
        if (!Files.isDirectory(path)) {
            return path;
        }
        try (Stream<Path> children = Files.list(path)) {
            List<Path> files = new ArrayList<>(children.filter(Files::isRegularFile).toList());
            if (files.size() != 1) {
                throw new IOException("Expected exactly one file in " + path + " but found " + files.size());
            }
            return files.get(0);
        }
    }

    private static X509Certificate readCertificate(Path file) throws IOException, CertificateException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return Identities.readX509Certificate(reader);
        }
    }

    private static PrivateKey readPrivateKey(Path file) throws IOException, InvalidKeyException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return Identities.readPrivateKey(reader);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @Override
    public synchronized void destroy() {
        if (serviceGateway != null) {
            serviceGateway.close();
        }
        userGateways.values().forEach(c -> c.gateway().close());
        userGateways.clear();
        if (channel != null) {
            channel.shutdownNow();
        }
    }
}

package com.blockevidence.backend.ledger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.blockevidence.backend.domain.Cid;
import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;
import com.blockevidence.backend.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import static com.blockevidence.backend.ledger.LedgerErrorCode.EVIDENCE_EXISTS;
import static com.blockevidence.backend.ledger.LedgerErrorCode.EVIDENCE_NOT_FOUND;
import static com.blockevidence.backend.ledger.LedgerErrorCode.FORBIDDEN_ROLE;
import static com.blockevidence.backend.ledger.LedgerErrorCode.INVALID_ARGUMENT;
import static com.blockevidence.backend.ledger.LedgerErrorCode.INVALID_STATE;
import static com.blockevidence.backend.ledger.LedgerErrorCode.VERSION_CONFLICT;

/**
 * REFERENCE / DEV-TEST LEDGER. NOT A BLOCKCHAIN, NOT TAMPER-PROOF, NOT PERSISTENT. Active only under the
 * {@code memory-ledger} profile.
 *
 * <p>It exists because Phase 2's chaincode is still awaiting design approval (docs/CHAINCODE_DESIGN.md),
 * yet the Spring flows (register, verify, update, dispose) need something to run against. It enforces
 * exactly the rules that document specifies, so it doubles as an executable specification: the same
 * scenarios are later run against the real chaincode.
 *
 * <p>Rules mirrored from the design: role table per write, expectedVersion check, immutable file fields,
 * DISPOSED freezes the record, approver cannot be the requester, disposal approval pins the reviewed
 * version, append-only history, CID index, and NO delete of any kind (C-02).
 */
@Service
@Profile("memory-ledger")
public class InMemoryLedgerService implements LedgerService {

    private static final Logger log = LoggerFactory.getLogger(InMemoryLedgerService.class);

    private static final Pattern EVIDENCE_ID = Pattern.compile("^EV-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$");
    private static final Pattern CASE_ID = Pattern.compile("^[A-Za-z0-9._/-]{1,64}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern USER_ID = Pattern.compile("^[0-9a-fA-F-]{36}$");

    // The role table from CHAINCODE_DESIGN.md section 4. Keep in step with the chaincode.
    static final Set<Role> CAN_CREATE_OR_UPDATE = EnumSet.of(Role.COLLECTOR, Role.FORENSIC_ANALYST);
    static final Set<Role> CAN_CHANGE_STATUS = EnumSet.of(Role.COLLECTOR, Role.FORENSIC_ANALYST, Role.PROSECUTOR);
    static final Set<Role> CAN_REQUEST_DISPOSAL = EnumSet.of(Role.COLLECTOR, Role.PROSECUTOR);
    static final Set<Role> CAN_DECIDE_DISPOSAL = EnumSet.of(Role.JUDGE);

    private record Version(String txId, Instant timestamp, LedgerEvidenceRecord record) {
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private final Clock clock;
    private final Map<String, List<Version>> history = new HashMap<>();
    private final Map<String, Set<String>> cidIndex = new HashMap<>();

    public InMemoryLedgerService(Clock clock) {
        this.clock = clock;
        log.warn("*** memory-ledger profile active: using the IN-MEMORY reference ledger. It is NOT a blockchain, "
                + "NOT tamper-proof and loses all data on restart. Development and testing only. ***");
    }

    // ------------------------------------------------------------------------------------- writes

    @Override
    public synchronized LedgerTxResult createEvidence(LedgerNewEvidence e, LedgerActor actor) {
        requireRole(actor, CAN_CREATE_OR_UPDATE, "create evidence");
        require(EVIDENCE_ID.matcher(nz(e.evidenceId())).matches(), "evidenceId is malformed");
        require(CASE_ID.matcher(nz(e.caseId())).matches(), "caseId is malformed");
        require(e.evidenceType() != null, "evidenceType is required");
        requireCidAndHash(e.metadataCid(), e.metadataSha256(), "metadata");
        if (e.evidenceType() == EvidenceType.DIGITAL) {
            requireCidAndHash(e.fileCid(), e.fileSha256(), "file");
            require(e.fileSize() != null && e.fileSize() > 0, "fileSize must be positive for DIGITAL evidence");
        } else {
            require(e.fileCid() == null && e.fileSha256() == null && e.fileSize() == null,
                    "PHYSICAL evidence must not carry file fields");
        }
        if (history.containsKey(e.evidenceId())) {
            throw new LedgerException(EVIDENCE_EXISTS, "Evidence " + e.evidenceId() + " already exists");
        }
        Version ts = stamp();
        LedgerEvidenceRecord record = new LedgerEvidenceRecord(e.evidenceId(), e.caseId(), e.evidenceType(),
                EvidenceStatus.COLLECTED, 1, e.metadataCid(), e.metadataSha256(), e.fileCid(), e.fileSha256(),
                e.fileSize(), actor.userId(), actor.role().name(), ts.timestamp(), actor.userId(),
                actor.role().name(), ts.timestamp(), LedgerAction.CREATED, "", actor.userId(),
                LedgerEvidenceRecord.Disposal.none());
        history.put(e.evidenceId(), new ArrayList<>(List.of(new Version(ts.txId(), ts.timestamp(), record))));
        index(e.evidenceId(), e.metadataCid(), e.fileCid());
        return new LedgerTxResult(ts.txId(), ts.timestamp());
    }

    @Override
    public synchronized LedgerTxResult updateEvidence(String id, int expectedVersion, String newMetadataCid,
            String newMetadataSha256, String reason, LedgerActor actor) {
        requireRole(actor, CAN_CREATE_OR_UPDATE, "update evidence");
        LedgerEvidenceRecord cur = editable(id, expectedVersion);
        requireCidAndHash(newMetadataCid, newMetadataSha256, "metadata");
        requireReason(reason);
        require(!newMetadataCid.equals(cur.metadataCid()), "The metadata CID is unchanged");
        Version ts = stamp();
        // File fields are copied from the current record untouched: they are immutable by design.
        LedgerEvidenceRecord next = new LedgerEvidenceRecord(cur.evidenceId(), cur.caseId(), cur.evidenceType(),
                cur.status(), cur.version() + 1, newMetadataCid, newMetadataSha256, cur.fileCid(), cur.fileSha256(),
                cur.fileSize(), cur.createdBy(), cur.createdByRole(), cur.createdAt(), actor.userId(),
                actor.role().name(), ts.timestamp(), LedgerAction.METADATA_UPDATED, reason, cur.currentCustodian(),
                cur.disposal());
        append(id, ts, next);
        index(id, newMetadataCid);
        return new LedgerTxResult(ts.txId(), ts.timestamp());
    }

    @Override
    public synchronized LedgerTxResult updateStatus(String id, int expectedVersion, EvidenceStatus newStatus,
            String reason, LedgerActor actor) {
        requireRole(actor, CAN_CHANGE_STATUS, "change status");
        LedgerEvidenceRecord cur = editable(id, expectedVersion);
        requireReason(reason);
        require(newStatus != null, "newStatus is required");
        require(newStatus != EvidenceStatus.DISPOSED, "DISPOSED is only reachable through an approved disposal");
        if (!cur.status().canMoveTo(newStatus)) {
            throw new LedgerException(INVALID_STATE, "Status cannot move from " + cur.status() + " to " + newStatus);
        }
        Version ts = stamp();
        append(id, ts, evolve(cur, actor, ts, LedgerAction.STATUS_CHANGED, reason, newStatus, cur.disposal()));
        return new LedgerTxResult(ts.txId(), ts.timestamp());
    }

    @Override
    public synchronized LedgerTxResult requestDisposal(String id, int expectedVersion, String reason, LedgerActor actor) {
        requireRole(actor, CAN_REQUEST_DISPOSAL, "request disposal");
        LedgerEvidenceRecord cur = editable(id, expectedVersion);
        requireReason(reason);
        if (cur.disposal().state() == LedgerEvidenceRecord.DisposalState.PENDING) {
            throw new LedgerException(INVALID_STATE, "A disposal request is already pending");
        }
        Version ts = stamp();
        var pending = new LedgerEvidenceRecord.Disposal(LedgerEvidenceRecord.DisposalState.PENDING, actor.userId(),
                ts.timestamp(), reason);
        append(id, ts, evolve(cur, actor, ts, LedgerAction.DISPOSAL_REQUESTED, reason, cur.status(), pending));
        return new LedgerTxResult(ts.txId(), ts.timestamp());
    }

    @Override
    public synchronized LedgerTxResult approveDisposal(String id, int expectedVersion, String note, LedgerActor actor) {
        requireRole(actor, CAN_DECIDE_DISPOSAL, "approve disposal");
        LedgerEvidenceRecord cur = editable(id, expectedVersion);
        requireReason(note);
        requirePending(cur);
        if (actor.userId().equals(cur.disposal().requestedBy())) {
            throw new LedgerException(FORBIDDEN_ROLE, "The approver cannot be the person who requested disposal");
        }
        Version ts = stamp();
        append(id, ts, evolve(cur, actor, ts, LedgerAction.DISPOSAL_APPROVED, note, EvidenceStatus.DISPOSED,
                LedgerEvidenceRecord.Disposal.none()));
        return new LedgerTxResult(ts.txId(), ts.timestamp());
    }

    @Override
    public synchronized LedgerTxResult rejectDisposal(String id, int expectedVersion, String note, LedgerActor actor) {
        requireRole(actor, CAN_DECIDE_DISPOSAL, "reject disposal");
        LedgerEvidenceRecord cur = editable(id, expectedVersion);
        requireReason(note);
        requirePending(cur);
        Version ts = stamp();
        append(id, ts, evolve(cur, actor, ts, LedgerAction.DISPOSAL_REJECTED, note, cur.status(),
                LedgerEvidenceRecord.Disposal.none()));
        return new LedgerTxResult(ts.txId(), ts.timestamp());
    }

    // -------------------------------------------------------------------------------------- reads

    @Override
    public synchronized Optional<LedgerEvidenceRecord> getEvidence(String id) {
        List<Version> versions = history.get(id);
        return versions == null ? Optional.empty() : Optional.of(versions.get(versions.size() - 1).record());
    }

    @Override
    public synchronized List<LedgerHistoryEntry> getHistory(String id) {
        List<Version> versions = history.get(id);
        if (versions == null) {
            throw new LedgerException(EVIDENCE_NOT_FOUND, "Evidence " + id + " does not exist");
        }
        return versions.stream().map(v -> new LedgerHistoryEntry(v.txId(), v.timestamp(), v.record())).toList();
    }

    @Override
    public synchronized List<String> findEvidenceIdsByCid(String cid) {
        return List.copyOf(cidIndex.getOrDefault(cid, Set.of()));
    }

    @Override
    public LedgerHealth health() {
        return new LedgerHealth(LedgerHealth.State.UP,
                "IN-MEMORY reference ledger (dev/test only). NOT a blockchain, NOT tamper-proof.");
    }

    // ------------------------------------------------------------------------------------ helpers

    /** Loads the record for a write, applying the checks every write shares: exists, not DISPOSED, version. */
    private LedgerEvidenceRecord editable(String id, int expectedVersion) {
        LedgerEvidenceRecord cur = getEvidence(id)
                .orElseThrow(() -> new LedgerException(EVIDENCE_NOT_FOUND, "Evidence " + id + " does not exist"));
        if (cur.status() == EvidenceStatus.DISPOSED) {
            throw new LedgerException(INVALID_STATE, "Evidence is DISPOSED and can no longer change");
        }
        if (cur.version() != expectedVersion) {
            throw new LedgerException(VERSION_CONFLICT,
                    "Expected version " + expectedVersion + " but the record is at version " + cur.version());
        }
        return cur;
    }

    private static LedgerEvidenceRecord evolve(LedgerEvidenceRecord cur, LedgerActor actor, Version ts,
            LedgerAction action, String reason, EvidenceStatus status, LedgerEvidenceRecord.Disposal disposal) {
        return new LedgerEvidenceRecord(cur.evidenceId(), cur.caseId(), cur.evidenceType(), status,
                cur.version() + 1, cur.metadataCid(), cur.metadataSha256(), cur.fileCid(), cur.fileSha256(),
                cur.fileSize(), cur.createdBy(), cur.createdByRole(), cur.createdAt(), actor.userId(),
                actor.role().name(), ts.timestamp(), action, reason, cur.currentCustodian(), disposal);
    }

    private void append(String id, Version ts, LedgerEvidenceRecord record) {
        history.get(id).add(new Version(ts.txId(), ts.timestamp(), record));
    }

    private void index(String id, String... cids) {
        for (String cid : cids) {
            if (cid != null) {
                cidIndex.computeIfAbsent(cid, k -> new LinkedHashSet<>()).add(id);
            }
        }
    }

    /** A fresh transaction id and the ledger's own timestamp (C4), never supplied by the caller. */
    private Version stamp() {
        byte[] txBytes = new byte[32];
        RANDOM.nextBytes(txBytes);
        return new Version(HexFormat.of().formatHex(txBytes), clock.instant(), null);
    }

    private static void requireRole(LedgerActor actor, Set<Role> allowed, String what) {
        require(actor != null && actor.userId() != null && USER_ID.matcher(actor.userId()).matches()
                && actor.role() != null, "actor is malformed");
        if (!allowed.contains(actor.role())) {
            throw new LedgerException(FORBIDDEN_ROLE, "Role " + actor.role() + " may not " + what);
        }
    }

    private static void requirePending(LedgerEvidenceRecord cur) {
        if (cur.disposal().state() != LedgerEvidenceRecord.DisposalState.PENDING) {
            throw new LedgerException(INVALID_STATE, "There is no pending disposal request");
        }
    }

    private static void requireReason(String reason) {
        require(reason != null && !reason.isBlank(), "A reason is required");
    }

    private static void requireCidAndHash(String cid, String sha256, String what) {
        require(Cid.isValid(cid), what + " CID is malformed");
        require(SHA256.matcher(nz(sha256)).matches(), what + " SHA-256 must be 64 lowercase hex characters");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new LedgerException(INVALID_ARGUMENT, message);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}

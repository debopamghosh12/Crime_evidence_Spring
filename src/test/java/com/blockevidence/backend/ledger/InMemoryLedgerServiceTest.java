package com.blockevidence.backend.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;
import com.blockevidence.backend.security.Role;
import com.blockevidence.backend.support.FakeIpfsClient;
import org.junit.jupiter.api.Test;

/**
 * The executable specification of docs/CHAINCODE_DESIGN.md: every rule the chaincode must enforce is
 * asserted here first, so the Go tests can be written against the same list.
 */
class InMemoryLedgerServiceTest {

    static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    final InMemoryLedgerService ledger = new InMemoryLedgerService(Clock.fixed(NOW, ZoneOffset.UTC));

    final String meta1 = FakeIpfsClient.cidOf("meta-1".getBytes());
    final String meta2 = FakeIpfsClient.cidOf("meta-2".getBytes());
    final String file = FakeIpfsClient.cidOf("file".getBytes());
    final String sha = "a".repeat(64);

    LedgerActor actor(Role role) {
        return new LedgerActor(UUID.randomUUID().toString(), role);
    }

    final LedgerActor collector = actor(Role.COLLECTOR);
    final LedgerActor analyst = actor(Role.FORENSIC_ANALYST);
    final LedgerActor prosecutor = actor(Role.PROSECUTOR);
    final LedgerActor judge = actor(Role.JUDGE);

    static String newId() {
        return "EV-" + UUID.randomUUID();
    }

    LedgerNewEvidence digital(String id) {
        return new LedgerNewEvidence(id, "CASE-1", EvidenceType.DIGITAL, meta1, sha, file, sha, 10L);
    }

    String created() {
        String id = newId();
        ledger.createEvidence(digital(id), collector);
        return id;
    }

    static LedgerErrorCode codeOf(Throwable t) {
        return ((LedgerException) t).ledgerCode();
    }

    // ------------------------------------------------------------------------------------ create

    @Test
    void createStoresVersionOneCollectedWithLedgerTimestampAndActor() {
        String id = newId();
        LedgerTxResult tx = ledger.createEvidence(digital(id), collector);

        LedgerEvidenceRecord r = ledger.getEvidence(id).orElseThrow();
        assertThat(r.version()).isEqualTo(1);
        assertThat(r.status()).isEqualTo(EvidenceStatus.COLLECTED);
        assertThat(r.createdBy()).isEqualTo(collector.userId());
        assertThat(r.currentCustodian()).isEqualTo(collector.userId());
        assertThat(r.createdAt()).isEqualTo(NOW);                    // C4: ledger clock, not the caller's
        assertThat(tx.txId()).hasSize(64);
        assertThat(r.fileSha256()).isEqualTo(sha);
        assertThat(r.lastAction()).isEqualTo(LedgerAction.CREATED);
    }

    @Test
    void duplicateIdIsRejected() {
        String id = created();
        assertThatThrownBy(() -> ledger.createEvidence(digital(id), collector))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.EVIDENCE_EXISTS));
    }

    @Test
    void onlyCollectorAndAnalystMayCreate() {
        for (Role role : Role.values()) {
            LedgerActor a = actor(role);
            if (role == Role.COLLECTOR || role == Role.FORENSIC_ANALYST) {
                ledger.createEvidence(digital(newId()), a);
            } else {
                assertThatThrownBy(() -> ledger.createEvidence(digital(newId()), a))
                        .as("role %s", role)
                        .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.FORBIDDEN_ROLE));
            }
        }
    }

    @Test
    void digitalNeedsFileFieldsAndPhysicalForbidsThem() {
        assertThatThrownBy(() -> ledger.createEvidence(
                new LedgerNewEvidence(newId(), "C", EvidenceType.DIGITAL, meta1, sha, null, null, null), collector))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> ledger.createEvidence(
                new LedgerNewEvidence(newId(), "C", EvidenceType.PHYSICAL, meta1, sha, file, sha, 5L), collector))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.INVALID_ARGUMENT));
        ledger.createEvidence(new LedgerNewEvidence(newId(), "C", EvidenceType.PHYSICAL, meta1, sha, null, null, null),
                collector);
    }

    @Test
    void malformedInputsAreRejected() {
        assertThatThrownBy(() -> ledger.createEvidence(digital("EV-not-a-uuid"), collector))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> ledger.createEvidence(new LedgerNewEvidence(newId(), "C", EvidenceType.PHYSICAL,
                "not-a-cid", sha, null, null, null), collector))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> ledger.createEvidence(new LedgerNewEvidence(newId(), "C", EvidenceType.PHYSICAL,
                meta1, "SHORT", null, null, null), collector))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> ledger.createEvidence(digital(newId()), new LedgerActor("not-a-uuid", Role.COLLECTOR)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.INVALID_ARGUMENT));
    }

    // ------------------------------------------------------------------------------------ update

    @Test
    void updateBumpsVersionKeepsFileFieldsAndNeedsAReason() {
        String id = created();
        LedgerEvidenceRecord before = ledger.getEvidence(id).orElseThrow();

        assertThatThrownBy(() -> ledger.updateEvidence(id, 1, meta2, sha, " ", analyst))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.INVALID_ARGUMENT));

        ledger.updateEvidence(id, 1, meta2, sha, "fix typo", analyst);
        LedgerEvidenceRecord after = ledger.getEvidence(id).orElseThrow();
        assertThat(after.version()).isEqualTo(2);
        assertThat(after.metadataCid()).isEqualTo(meta2);
        assertThat(after.lastReason()).isEqualTo("fix typo");
        assertThat(after.updatedBy()).isEqualTo(analyst.userId());
        assertThat(after.createdBy()).isEqualTo(collector.userId());     // creator is never rewritten
        // Immutability of the evidence file (design section 3):
        assertThat(after.fileCid()).isEqualTo(before.fileCid());
        assertThat(after.fileSha256()).isEqualTo(before.fileSha256());
        assertThat(after.fileSize()).isEqualTo(before.fileSize());
    }

    @Test
    void staleExpectedVersionIsRejected() {
        String id = created();
        ledger.updateEvidence(id, 1, meta2, sha, "r", collector);
        assertThatThrownBy(() -> ledger.updateEvidence(id, 1, FakeIpfsClient.cidOf("x".getBytes()), sha, "r", collector))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.VERSION_CONFLICT));
    }

    @Test
    void updateWithUnchangedCidOrUnknownIdOrWrongRoleFails() {
        String id = created();
        assertThatThrownBy(() -> ledger.updateEvidence(id, 1, meta1, sha, "r", collector))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> ledger.updateEvidence(newId(), 1, meta2, sha, "r", collector))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.EVIDENCE_NOT_FOUND));
        assertThatThrownBy(() -> ledger.updateEvidence(id, 1, meta2, sha, "r", judge))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.FORBIDDEN_ROLE));
    }

    // ------------------------------------------------------------------------------------ status

    @Test
    void statusMovesForwardOnlyAndNeverToDisposed() {
        String id = created();
        ledger.updateStatus(id, 1, EvidenceStatus.PROCESSING, "r", analyst);
        assertThatThrownBy(() -> ledger.updateStatus(id, 2, EvidenceStatus.ARCHIVED, "skip", analyst))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.INVALID_STATE));
        assertThatThrownBy(() -> ledger.updateStatus(id, 2, EvidenceStatus.COLLECTED, "back", analyst))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.INVALID_STATE));
        assertThatThrownBy(() -> ledger.updateStatus(id, 2, EvidenceStatus.DISPOSED, "shortcut", prosecutor))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> ledger.updateStatus(id, 2, EvidenceStatus.ANALYZED, "r", judge))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.FORBIDDEN_ROLE));
    }

    // ----------------------------------------------------------------------------------- disposal

    @Test
    void disposalNeedsRequestThenJudgeApprovalAndThenFreezesTheRecordButKeepsIt() {
        String id = created();

        assertThatThrownBy(() -> ledger.approveDisposal(id, 1, "ok", judge))
                .as("nothing to approve yet")
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.INVALID_STATE));
        assertThatThrownBy(() -> ledger.requestDisposal(id, 1, "why", judge))
                .as("a judge cannot request")
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.FORBIDDEN_ROLE));

        ledger.requestDisposal(id, 1, "case closed", prosecutor);
        assertThat(ledger.getEvidence(id).orElseThrow().disposal().state())
                .isEqualTo(LedgerEvidenceRecord.DisposalState.PENDING);
        assertThatThrownBy(() -> ledger.requestDisposal(id, 2, "again", collector))
                .as("already pending")
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.INVALID_STATE));
        assertThatThrownBy(() -> ledger.approveDisposal(id, 2, "ok", prosecutor))
                .as("only a judge decides")
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.FORBIDDEN_ROLE));
        assertThatThrownBy(() -> ledger.approveDisposal(id, 1, "ok", judge))
                .as("approver must have reviewed the current version")
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.VERSION_CONFLICT));

        ledger.approveDisposal(id, 2, "approved", judge);
        LedgerEvidenceRecord r = ledger.getEvidence(id).orElseThrow();
        assertThat(r.status()).isEqualTo(EvidenceStatus.DISPOSED);
        assertThat(r.version()).isEqualTo(3);
        assertThat(ledger.getHistory(id)).hasSize(3);                    // the record and its history remain

        // Frozen: no further write of any kind.
        assertThatThrownBy(() -> ledger.updateEvidence(id, 3, meta2, sha, "r", collector))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.INVALID_STATE));
        assertThatThrownBy(() -> ledger.requestDisposal(id, 3, "r", collector))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.INVALID_STATE));
    }

    @Test
    void rejectedDisposalLeavesTheRecordUsable() {
        String id = created();
        ledger.requestDisposal(id, 1, "r", collector);
        ledger.rejectDisposal(id, 2, "not yet", judge);

        LedgerEvidenceRecord r = ledger.getEvidence(id).orElseThrow();
        assertThat(r.status()).isEqualTo(EvidenceStatus.COLLECTED);
        assertThat(r.disposal().state()).isEqualTo(LedgerEvidenceRecord.DisposalState.NONE);
        ledger.updateEvidence(id, 3, meta2, sha, "still editable", collector);
    }

    // -------------------------------------------------------------------------- history and index

    @Test
    void historyIsAppendOnlyOldestFirstWithDistinctTxIds() {
        String id = created();
        ledger.updateEvidence(id, 1, meta2, sha, "r", collector);
        ledger.requestDisposal(id, 2, "r", collector);

        List<LedgerHistoryEntry> h = ledger.getHistory(id);
        assertThat(h).extracting(x -> x.record().version()).containsExactly(1, 2, 3);
        assertThat(h).extracting(x -> x.record().lastAction())
                .containsExactly(LedgerAction.CREATED, LedgerAction.METADATA_UPDATED, LedgerAction.DISPOSAL_REQUESTED);
        assertThat(h).extracting(LedgerHistoryEntry::txId).doesNotHaveDuplicates();
        assertThat(h.get(0).record().metadataCid()).isEqualTo(meta1);    // the old version is still readable
        assertThat(h.get(1).record().metadataCid()).isEqualTo(meta2);
        assertThatThrownBy(() -> ledger.getHistory(newId()))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(LedgerErrorCode.EVIDENCE_NOT_FOUND));
    }

    @Test
    void cidIndexFindsFileAndEveryMetadataCidAndSharedFiles() {
        String a = created();
        String b = created();                                             // same file CID as a
        ledger.updateEvidence(a, 1, meta2, sha, "r", collector);

        assertThat(ledger.findEvidenceIdsByCid(file)).containsExactlyInAnyOrder(a, b);
        assertThat(ledger.findEvidenceIdsByCid(meta2)).containsExactly(a);
        assertThat(ledger.findEvidenceIdsByCid(meta1)).containsExactlyInAnyOrder(a, b);   // old CIDs stay indexed
        assertThat(ledger.findEvidenceIdsByCid(FakeIpfsClient.cidOf("nope".getBytes()))).isEmpty();
    }

    // ---------------------------------------------------------------------------------- C-02

    @Test
    void thereIsNoWayToDeleteEvidence() {
        List<String> names = Arrays.stream(LedgerService.class.getMethods()).map(Method::getName).toList();
        assertThat(names).noneMatch(n -> n.toLowerCase().matches(".*(delete|remove|purge|erase|destroy).*"));
    }
}

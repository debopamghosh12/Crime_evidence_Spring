package com.blockevidence.backend.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.blockevidence.backend.config.FabricProperties;
import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;
import com.blockevidence.backend.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * FabricLedgerService without a network. The JSON fixtures in src/test/resources/fabric are REAL output
 * captured from the deployed chaincode on the Fabric peers (see TEST_CHECKLIST.md), so these tests pin the
 * Go-to-Java wire format against what the chaincode actually emits, not against what was assumed.
 */
class FabricLedgerServiceTest {

    final JsonMapper vanilla = JsonMapper.builder().build();   // NOT Boot's lenient mapper: the service must cope itself

    /** A2: no user has a wallet entry, i.e. exactly a freshly deployed backend before enroll_users.sh has run. */
    static class StubIdentityStore implements IdentityStore {
        final Map<String, WalletIdentity> byUser = new HashMap<>();

        @Override
        public Optional<WalletIdentity> find(String userId) {
            return Optional.ofNullable(byUser.get(userId));
        }

        @Override
        public List<String> expiringWithin(int days) {
            return List.of();
        }
    }

    final StubIdentityStore identityStore = new StubIdentityStore();

    FabricProperties props(String tls, String cert, String key) {
        return new FabricProperties("crimechannel", "evidence", "localhost:7051", "peer0.org1.example.com",
                "Org1MSP", tls, cert, key, null, null, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1));
    }

    FabricLedgerService unconfigured() {
        return new FabricLedgerService(props(null, null, null), identityStore, vanilla);
    }

    static byte[] fixture(String name) throws IOException {
        try (var in = FabricLedgerServiceTest.class.getResourceAsStream("/fabric/" + name)) {
            return in.readAllBytes();
        }
    }

    static String text(String name) throws IOException {
        return new String(fixture(name), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ wire format (real peer output)

    @Test
    void parsesTheTransactionResultOfARealCommittedWrite() throws IOException {
        var tx = unconfigured().parse(fixture("tx-result.json"), FabricLedgerService.WireTxResult.class);

        assertThat(tx.txId()).matches("[0-9a-f]{64}");
        assertThat(tx.timestamp()).isAfter(Instant.parse("2026-01-01T00:00:00Z"));   // the ledger's own time
        assertThat(tx.version()).isEqualTo(1);
    }

    @Test
    void parsesARealPhysicalRecordWhoseFileAndDisposalFieldsAreAbsent() throws IOException {
        // This shape is exactly what failed the contract's schema check on the first live query.
        LedgerEvidenceRecord r = unconfigured().parse(fixture("get-physical.json"), LedgerEvidenceRecord.class);

        assertThat(r.evidenceType()).isEqualTo(EvidenceType.PHYSICAL);
        assertThat(r.status()).isEqualTo(EvidenceStatus.COLLECTED);
        assertThat(r.version()).isEqualTo(1);
        assertThat(r.fileCid()).isNull();
        assertThat(r.fileSha256()).isNull();
        assertThat(r.fileSize()).isNull();
        assertThat(r.disposal().state()).isEqualTo(LedgerEvidenceRecord.DisposalState.NONE);
        assertThat(r.disposal().requestedAt()).isNull();
        assertThat(r.createdAt()).isEqualTo(r.updatedAt());
        assertThat(r.lastAction()).isEqualTo(LedgerAction.CREATED);
        assertThat(r.currentCustodian()).isEqualTo(r.createdBy());
    }

    @Test
    void parsesARealDisposedDigitalRecord() throws IOException {
        LedgerEvidenceRecord r = unconfigured().parse(fixture("get-digital-disposed.json"), LedgerEvidenceRecord.class);

        assertThat(r.evidenceType()).isEqualTo(EvidenceType.DIGITAL);
        assertThat(r.status()).isEqualTo(EvidenceStatus.DISPOSED);
        assertThat(r.version()).isEqualTo(5);
        assertThat(r.fileSize()).isEqualTo(1234L);
        assertThat(r.fileCid()).startsWith("b");
        assertThat(r.fileSha256()).hasSize(64);
        assertThat(r.lastAction()).isEqualTo(LedgerAction.DISPOSAL_APPROVED);
        assertThat(r.updatedByRole()).isEqualTo("JUDGE");
        assertThat(r.disposal().state()).isEqualTo(LedgerEvidenceRecord.DisposalState.NONE);
    }

    @Test
    void parsesARealHistoryOldestFirstWithLedgerTxIdsAndTimestamps() throws IOException {
        List<LedgerHistoryEntry> h = unconfigured().parse(fixture("history-digital.json"),
                new TypeReference<List<LedgerHistoryEntry>>() {
                });

        assertThat(h).extracting(e -> e.record().version()).containsExactly(1, 2, 3, 4, 5);
        assertThat(h).extracting(e -> e.record().lastAction()).containsExactly(LedgerAction.CREATED,
                LedgerAction.METADATA_UPDATED, LedgerAction.STATUS_CHANGED, LedgerAction.DISPOSAL_REQUESTED,
                LedgerAction.DISPOSAL_APPROVED);
        assertThat(h).extracting(LedgerHistoryEntry::txId).allMatch(t -> t.matches("[0-9a-f]{64}")).doesNotHaveDuplicates();
        assertThat(h).extracting(LedgerHistoryEntry::timestamp).isSorted();
        // the old version is still there with its own metadata pointer
        assertThat(h.get(0).record().metadataCid()).isNotEqualTo(h.get(1).record().metadataCid());
        assertThat(h.get(1).record().metadataCid()).isEqualTo(h.get(4).record().metadataCid());
    }

    @Test
    void parsesCidLookupResultsIncludingNoMatch() throws IOException {
        assertThat(unconfigured().parse(fixture("find-by-cid.json"), new TypeReference<List<String>>() {
        })).hasSize(1).allMatch(id -> id.startsWith("EV-"));
        assertThat(unconfigured().parse(fixture("find-by-cid-none.json"), new TypeReference<List<String>>() {
        })).isEmpty();
    }

    // ------------------------------------------- Phase 3 wire format (real peer output, chaincode v1.2 / sequence 3)

    @Test
    void parsesARealRecordWithAPendingTransfer() throws IOException {
        LedgerEvidenceRecord r = unconfigured().parse(fixture("p3-get-pending.json"), LedgerEvidenceRecord.class);

        assertThat(r.lastAction()).isEqualTo(LedgerAction.TRANSFER_INITIATED);
        assertThat(r.transfer().state()).isEqualTo(LedgerEvidenceRecord.TransferState.PENDING);
        assertThat(r.transfer().from()).isEqualTo("11111111-1111-4111-8111-111111111111");
        assertThat(r.transfer().to()).isEqualTo("22222222-2222-4222-8222-222222222222");
        assertThat(r.transfer().toRole()).isEqualTo(Role.FORENSIC_ANALYST);
        assertThat(r.transfer().notes()).isEqualTo("sealed bag 7");
        assertThat(r.transfer().initiatedAt()).isNotNull();
        assertThat(r.transfer().resolvedAt()).isNull();
        assertThat(r.currentCustodian()).as("custody has not moved yet").isEqualTo(r.transfer().from());
    }

    @Test
    void parsesARealRecordAfterAcceptanceAndOneThatHasNeverBeenTransferred() throws IOException {
        LedgerEvidenceRecord accepted = unconfigured().parse(fixture("p3-get-accepted.json"), LedgerEvidenceRecord.class);
        assertThat(accepted.transfer().state()).isEqualTo(LedgerEvidenceRecord.TransferState.ACCEPTED);
        assertThat(accepted.transfer().resolutionNote()).isEqualTo("received intact");
        assertThat(accepted.transfer().resolvedAt()).isNotNull();
        assertThat(accepted.currentCustodian()).isEqualTo("22222222-2222-4222-8222-222222222222");
        assertThat(accepted.createdBy()).isEqualTo("11111111-1111-4111-8111-111111111111");

        LedgerEvidenceRecord fresh = unconfigured().parse(fixture("p3-get-new.json"), LedgerEvidenceRecord.class);
        assertThat(fresh.transfer().state()).isEqualTo(LedgerEvidenceRecord.TransferState.NONE);
        assertThat(fresh.transfer().from()).isNull();
    }

    @Test
    void aRecordWrittenBeforeTransfersExistedStillParsesAsNoTransfer() throws IOException {
        // get-physical.json was captured on chaincode v1.1 and has no "transfer" key at all.
        assertThat(text("get-physical.json")).doesNotContain("\"transfer\"");
        LedgerEvidenceRecord r = unconfigured().parse(fixture("get-physical.json"), LedgerEvidenceRecord.class);

        assertThat(r.transfer()).isNotNull();
        assertThat(r.transfer().state()).isEqualTo(LedgerEvidenceRecord.TransferState.NONE);
    }

    @Test
    void parsesARealTransferHistoryAndPendingLists() throws IOException {
        List<LedgerHistoryEntry> h = unconfigured().parse(fixture("p3-history.json"), new TypeReference<List<LedgerHistoryEntry>>() {
        });
        assertThat(h).extracting(e -> e.record().lastAction()).containsExactly(LedgerAction.CREATED, LedgerAction.TRANSFER_INITIATED,
                LedgerAction.TRANSFER_ACCEPTED, LedgerAction.TRANSFER_INITIATED, LedgerAction.TRANSFER_REJECTED);
        assertThat(h).extracting(LedgerHistoryEntry::txId).allMatch(t -> t.matches("[0-9a-f]{64}")).doesNotHaveDuplicates();
        assertThat(h.get(4).record().currentCustodian()).isEqualTo("22222222-2222-4222-8222-222222222222");

        assertThat(unconfigured().parse(fixture("p3-find-pending.json"), new TypeReference<List<String>>() {
        })).hasSize(1).allMatch(id -> id.startsWith("EV-"));
        assertThat(unconfigured().parse(fixture("p3-find-pending-none.json"), new TypeReference<List<String>>() {
        })).isEmpty();
        assertThat(unconfigured().parse(fixture("p3-find-pending-after.json"), new TypeReference<List<String>>() {
        })).as("a resolved transfer leaves the pending list").isEmpty();
    }

    @Test
    void translatesTheRealPeerErrorsForTransfers() throws IOException {
        LedgerException forbidden = FabricErrors.translate(text("p3-error-forbidden-role.txt"), false, false);
        assertThat(forbidden.ledgerCode()).isEqualTo(LedgerErrorCode.FORBIDDEN_ROLE);
        assertThat(forbidden.getMessage()).isEqualTo("Only the named receiver can respond to this transfer");

        LedgerException state = FabricErrors.translate(text("p3-error-invalid-state.txt"), false, false);
        assertThat(state.ledgerCode()).isEqualTo(LedgerErrorCode.INVALID_STATE);
        assertThat(state.getStatus().value()).isEqualTo(409);
    }

    // ------------------------------------------------------------- error translation (real messages)

    @Test
    void translatesTheRealPeerErrorForAStaleVersion() throws IOException {
        LedgerException e = FabricErrors.translate(text("error-version-conflict.txt"), false, false);

        assertThat(e.ledgerCode()).isEqualTo(LedgerErrorCode.VERSION_CONFLICT);
        assertThat(e.getStatus().value()).isEqualTo(409);
        assertThat(e.getMessage()).isEqualTo("Expected version 9 but the record is at version 1");
    }

    @Test
    void translatesTheRealPeerErrorForAnUnknownId() throws IOException {
        LedgerException e = FabricErrors.translate(text("error-not-found.txt"), false, false);

        assertThat(e.ledgerCode()).isEqualTo(LedgerErrorCode.EVIDENCE_NOT_FOUND);
        assertThat(e.getStatus().value()).isEqualTo(404);
    }

    @Test
    void everyChaincodeCodeMapsToItsOwnLedgerErrorCode() {
        for (LedgerErrorCode code : LedgerErrorCode.values()) {
            if (code == LedgerErrorCode.LEDGER_UNAVAILABLE || code == LedgerErrorCode.LEDGER_IDENTITY_MISSING
                    || code == LedgerErrorCode.CONCURRENT_WRITE_CONFLICT) {
                continue;   // raised on the Java side (A2/F5), never returned by the chaincode itself
            }
            String wire = "endorsement failure during invoke. response: status:500 message:\"" + code.name() + ": detail here\"";
            LedgerException e = FabricErrors.translate(wire, false, false);
            assertThat(e.ledgerCode()).isEqualTo(code);
            assertThat(e.getMessage()).isEqualTo("detail here");
        }
    }

    @Test
    void theMessageStopsAtTheEndOfTheChaincodeFragmentAndDoesNotRunIntoTheGatewaysNextOne() {
        // Shape seen on a live run: the gateway's own text follows the chaincode's message as another fragment.
        String flattened = "ABORTED: failed to endorse transaction\n"
                + "endorse error on peer0.org1: chaincode response 500, INVALID_STATE: Evidence is DISPOSED and can no longer change\n"
                + "ABORTED: failed to endorse transaction, see attached details for more info";

        LedgerException e = FabricErrors.translate(flattened, false, false);

        assertThat(e.ledgerCode()).isEqualTo(LedgerErrorCode.INVALID_STATE);
        assertThat(e.getMessage()).isEqualTo("Evidence is DISPOSED and can no longer change");
    }

    @Test
    void anMvccCommitConflictBecomesConcurrentWriteConflictNotVersionConflict() {
        // F5: distinct from VERSION_CONFLICT on purpose - this one is safe to retry with the same arguments
        // (nothing committed), unlike a chaincode-reported stale expectedVersion.
        LedgerException e = FabricErrors.translate("transaction commit failed MVCC_READ_CONFLICT", false, true);
        assertThat(e.ledgerCode()).isEqualTo(LedgerErrorCode.CONCURRENT_WRITE_CONFLICT);
    }

    @Test
    void connectivityFailuresAndUnknownTextAreLedgerUnavailableAndNeverLeakDetail() {
        assertThat(FabricErrors.translate("UNAVAILABLE: io exception connect refused 127.0.0.1:7051", true, false).ledgerCode())
                .isEqualTo(LedgerErrorCode.LEDGER_UNAVAILABLE);
        LedgerException other = FabricErrors.translate("some internal stack trace with secret path /x/y", false, false);
        assertThat(other.ledgerCode()).isEqualTo(LedgerErrorCode.LEDGER_UNAVAILABLE);
        assertThat(other.getMessage()).doesNotContain("secret").doesNotContain("/x/y");
        // a code-looking word that is not a whole known code must not be mistaken for one
        assertThat(FabricErrors.translate("NOT_INVALID_STATE_REALLY", false, false).ledgerCode())
                .isEqualTo(LedgerErrorCode.LEDGER_UNAVAILABLE);
    }

    // ----------------------------------------------------- not configured / cannot connect (no network)

    @Test
    void withoutAServiceIdentityEveryReadIsLedgerUnavailableAndHealthIsUnknown() {
        FabricLedgerService service = unconfigured();
        String id = "EV-" + UUID.randomUUID();
        String cid = "b" + "a".repeat(52);

        // Reads always use the SERVICE identity (A2 does not touch them); unconfigured means LEDGER_UNAVAILABLE.
        List<Runnable> reads = List.of(
                () -> service.getEvidence(id),                 // must throw, NOT return Optional.empty()
                () -> service.getHistory(id),
                () -> service.findEvidenceIdsByCid(cid));
        for (Runnable call : reads) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(LedgerException.class,
                    e -> assertThat(e.ledgerCode()).isEqualTo(LedgerErrorCode.LEDGER_UNAVAILABLE));
        }
        LedgerHealth health = service.health();
        assertThat(health.state()).isEqualTo(LedgerHealth.State.UNKNOWN);
        assertThat(health.detail()).contains("not configured").contains("crimechannel").contains("evidence");
    }

    // ------------------------------------------------------------------------------------------------- A2

    @Test
    void aUserWithNoWalletEntryGetsLedgerIdentityMissingOnEveryWriteRegardlessOfTheServiceIdentity() {
        // Even with a (fake, unreadable) SERVICE identity "configured", a write is refused for the ACTOR's
        // missing wallet entry before any connection is attempted - never a silent fallback to the service identity.
        FabricLedgerService service = new FabricLedgerService(props("a", "b", "c"), identityStore, vanilla);
        var actor = new LedgerActor(UUID.randomUUID().toString(), Role.COLLECTOR);
        String id = "EV-" + UUID.randomUUID();
        String cid = "b" + "a".repeat(52);

        List<Runnable> writes = List.of(
                () -> service.createEvidence(new LedgerNewEvidence(id, "C", EvidenceType.PHYSICAL, cid, "a".repeat(64), null, null, null), actor),
                () -> service.updateEvidence(id, 1, cid, "a".repeat(64), "r", actor),
                () -> service.updateStatus(id, 1, EvidenceStatus.PROCESSING, "r", actor),
                () -> service.requestDisposal(id, 1, "r", actor),
                () -> service.approveDisposal(id, 1, "r", actor),
                () -> service.rejectDisposal(id, 1, "r", actor),
                () -> service.initiateTransfer(id, 1, UUID.randomUUID().toString(), Role.FORENSIC_ANALYST, "r", null, actor),
                () -> service.acceptTransfer(id, 1, "r", actor),
                () -> service.rejectTransfer(id, 1, "r", actor),
                () -> service.cancelTransfer(id, 1, "r", actor));
        for (Runnable call : writes) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(LedgerException.class, e -> {
                assertThat(e.ledgerCode()).isEqualTo(LedgerErrorCode.LEDGER_IDENTITY_MISSING);
                assertThat(e.getStatus().value()).isEqualTo(403);
                assertThat(e.getMessage()).doesNotContain(actor.userId());   // the user id itself is not secret, but nothing else leaks
            });
        }
    }

    @Test
    void findPendingTransfersIsAReadAndUsesTheServiceIdentityNotTheCaller() {
        // No actor is even passed to this call (LedgerService.findPendingTransferIds takes a bare userId): it can
        // only be a read, so an unconfigured service identity - not a missing wallet entry - is what it reports.
        assertThatThrownBy(() -> unconfigured().findPendingTransferIds(UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(LedgerException.class, e -> assertThat(e.ledgerCode()).isEqualTo(LedgerErrorCode.LEDGER_UNAVAILABLE));
    }

    @Test
    void unreadableIdentityFilesAreLedgerUnavailableAndHealthIsDown(@TempDir Path dir) {
        String missing = dir.resolve("does-not-exist.pem").toString();
        FabricLedgerService service = new FabricLedgerService(props(missing, missing, missing), identityStore, vanilla);

        assertThatThrownBy(() -> service.getEvidence("EV-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(LedgerException.class, e -> {
                    assertThat(e.ledgerCode()).isEqualTo(LedgerErrorCode.LEDGER_UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain(dir.toString());          // no filesystem paths in the API error
                });
        assertThat(service.health().state()).isEqualTo(LedgerHealth.State.DOWN);
    }

    @Test
    void propertiesReportWhetherTheServiceIdentityAndTheWalletAreConfigured() {
        assertThat(props(null, null, null).isConfigured()).isFalse();
        assertThat(props("a", "b", " ").isConfigured()).isFalse();
        assertThat(props("a", "b", "c").isConfigured()).isTrue();
        assertThat(props("a", "b", "c").toString()).doesNotContain("secret");
        assertThat(props(null, null, null).isWalletConfigured()).isFalse();   // walletDir defaults to null in props()
    }

    @Test
    void aConfiguredPathMayBeAFileOrADirectoryHoldingExactlyOneFile(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("cert.pem"), "x");
        assertThat(FabricLedgerService.resolveFile(file.toString())).isEqualTo(file);
        assertThat(FabricLedgerService.resolveFile(dir.toString())).isEqualTo(file);

        Files.writeString(dir.resolve("second.pem"), "y");
        assertThatThrownBy(() -> FabricLedgerService.resolveFile(dir.toString())).isInstanceOf(IOException.class);
        Path empty = Files.createDirectory(dir.resolve("empty"));
        assertThatThrownBy(() -> FabricLedgerService.resolveFile(empty.toString())).isInstanceOf(IOException.class);
    }
}

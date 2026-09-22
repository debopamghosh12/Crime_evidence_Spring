package com.blockevidence.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.blockevidence.backend.config.UploadProperties;
import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;
import com.blockevidence.backend.domain.VerificationStatus;
import com.blockevidence.backend.dto.DisposalDecisionBody;
import com.blockevidence.backend.dto.DisposalRequestBody;
import com.blockevidence.backend.dto.EvidenceResponse;
import com.blockevidence.backend.dto.RegisterEvidenceRequest;
import com.blockevidence.backend.dto.UpdateEvidenceRequest;
import com.blockevidence.backend.dto.VerificationResponse;
import com.blockevidence.backend.exception.ApiException;
import com.blockevidence.backend.ledger.InMemoryLedgerService;
import com.blockevidence.backend.ledger.LedgerException;
import com.blockevidence.backend.ledger.LedgerService;
import com.blockevidence.backend.model.CaseFile;
import com.blockevidence.backend.repository.CaseEvidenceLinkRepository;
import com.blockevidence.backend.repository.CaseFileRepository;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.security.Role;
import com.blockevidence.backend.storage.StorageUnavailableException;
import com.blockevidence.backend.support.FakeIpfsClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * B1-B5, C1-C3 end to end through EvidenceService, using the reference ledger and a fake IPFS node that
 * can be corrupted. Live equivalents against real Kubo are recorded in docs/TEST_CHECKLIST.md.
 *
 * <p>E3 (case validation): {@code cases} is stubbed to resolve ANY case number used below to one fixed, existing
 * case, so every test written before E3 keeps registering evidence exactly as it did; {@link #caseNotFound()}
 * covers the one new behaviour (an unknown caseId is refused) with its own dedicated stub.
 */
class EvidenceServiceTest {

    final Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
    final FakeIpfsClient ipfs = new FakeIpfsClient();
    final InMemoryLedgerService ledger = new InMemoryLedgerService(clock);
    final JsonMapper json = JsonMapper.builder().build();
    final UploadProperties upload = new UploadProperties(List.of("text/plain", "image/png"));
    final CaseFileRepository cases = mock(CaseFileRepository.class);
    final CaseEvidenceLinkRepository caseLinks = mock(CaseEvidenceLinkRepository.class);
    final EvidenceService service = build(ledger);

    EvidenceService build(LedgerService l) {
        CaseFile existingCase = new CaseFile("ANY-CASE", "t", null, UUID.randomUUID(), UUID.randomUUID(), clock.instant());
        ReflectionTestUtils.setField(existingCase, "id", UUID.randomUUID());
        lenient().when(cases.findByCaseNumberIgnoreCase(anyString())).thenReturn(Optional.of(existingCase));
        return new EvidenceService(l, ipfs, new VerificationService(ipfs, clock), json, upload, cases, caseLinks, clock);
    }

    AuthenticatedUser user(Role role) {
        return new AuthenticatedUser(UUID.randomUUID(), role.name().toLowerCase() + "@example.org", role);
    }

    final AuthenticatedUser collector = user(Role.COLLECTOR);
    final AuthenticatedUser analyst = user(Role.FORENSIC_ANALYST);
    final AuthenticatedUser prosecutor = user(Role.PROSECUTOR);
    final AuthenticatedUser judge = user(Role.JUDGE);

    static final byte[] CONTENT = "the seized phone image, byte for byte".getBytes(StandardCharsets.UTF_8);

    RegisterEvidenceRequest digitalRequest() {
        return new RegisterEvidenceRequest("CASE-2026-1", EvidenceType.DIGITAL, "Phone image", "Locker 4", null, null);
    }

    MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "phone.txt", "text/plain", content);
    }

    EvidenceResponse registerDigital() {
        return service.register(digitalRequest(), file(CONTENT), collector);
    }

    // ------------------------------------------------------------------------------ B1, B2, C1

    @Test
    void registerDigitalStoresFileAndMetadataInIpfsAndTheirHashesOnTheLedger() {
        EvidenceResponse r = registerDigital();

        assertThat(r.evidenceId()).matches("EV-[0-9a-f-]{36}");            // server-generated
        assertThat(r.status()).isEqualTo(EvidenceStatus.COLLECTED);
        assertThat(r.version()).isEqualTo(1);
        // C1: the ledger hash equals an independent SHA-256 of the uploaded bytes.
        assertThat(r.fileSha256()).isEqualTo(Sha256.hex(CONTENT));
        assertThat(r.fileSize()).isEqualTo(CONTENT.length);
        assertThat(ipfs.store.get(r.fileCid())).isEqualTo(CONTENT);
        assertThat(r.metadataSha256()).isEqualTo(Sha256.hex(ipfs.store.get(r.metadataCid())));
        assertThat(r.metadataAvailable()).isTrue();
        assertThat(r.metadata().description()).isEqualTo("Phone image");
        assertThat(r.metadata().file().sha256()).isEqualTo(r.fileSha256());
        assertThat(r.verification().status()).isEqualTo(VerificationStatus.NOT_CHECKED);
    }

    @Test
    void theRegistrantComesFromTheTokenAndIsWrittenToLedgerAndMetadata() {
        EvidenceResponse r = service.register(digitalRequest(), file(CONTENT), analyst);

        assertThat(r.createdBy()).isEqualTo(analyst.userId().toString());
        assertThat(r.currentCustodian()).isEqualTo(analyst.userId().toString());
        assertThat(r.metadata().collectorId()).isEqualTo(analyst.userId().toString());
    }

    @Test
    void registerPhysicalHasNoFileFields() {
        EvidenceResponse r = service.register(
                new RegisterEvidenceRequest("CASE-1", EvidenceType.PHYSICAL, "Knife", "Kitchen", null, "bagged"),
                null, collector);

        assertThat(r.fileCid()).isNull();
        assertThat(r.fileSha256()).isNull();
        assertThat(r.metadata().file()).isNull();
        assertThat(service.verify(r.evidenceId()).file()).isNull();
        assertThat(service.verify(r.evidenceId()).status()).isEqualTo(VerificationStatus.VERIFIED);
    }

    @Test
    void registeringLinksTheEvidenceToTheCaseAndAnUnknownCaseNumberIsRefusedBeforeAnyStorageWork() {
        registerDigital();
        verify(caseLinks, org.mockito.Mockito.times(1)).save(any());   // the happy-path register above created the E3 link

        when(cases.findByCaseNumberIgnoreCase("NO-SUCH-CASE")).thenReturn(Optional.empty());
        int storedBefore = ipfs.store.size();
        assertThatThrownBy(() -> service.register(
                new RegisterEvidenceRequest("NO-SUCH-CASE", EvidenceType.PHYSICAL, "d", null, null, null), null, collector))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("CASE_NOT_FOUND");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        // Nothing new was pinned to IPFS or linked for the rejected attempt: the case check runs first.
        assertThat(ipfs.store).hasSize(storedBefore);
        verify(caseLinks, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void fileAndTypeMustAgreeAndTypeMustBeAllowed() {
        assertThatThrownBy(() -> service.register(digitalRequest(), null, collector))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("FILE_REQUIRED"));
        assertThatThrownBy(() -> service.register(
                new RegisterEvidenceRequest("C", EvidenceType.PHYSICAL, "d", null, null, null), file(CONTENT), collector))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("FILE_NOT_ALLOWED"));
        assertThatThrownBy(() -> service.register(digitalRequest(),
                new MockMultipartFile("file", "x.exe", "application/x-msdownload", CONTENT), collector))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
                    assertThat(e.getCode()).isEqualTo("UNSUPPORTED_FILE_TYPE");
                });
        assertThat(ipfs.store).as("a rejected request must not reach IPFS").isEmpty();
    }

    @Test
    void contentTypeParametersAreIgnoredWhenCheckingTheAllowList() {
        MockMultipartFile f = new MockMultipartFile("file", "a.txt", "text/plain; charset=utf-8", CONTENT);
        assertThat(service.register(digitalRequest(), f, collector).fileCid()).isNotNull();
    }

    @Test
    void hostileFileNamesAreReducedToABareName() {
        assertThat(EvidenceService.safeFileName("..\\..\\windows\\system32\\evil.txt")).isEqualTo("evil.txt");
        assertThat(EvidenceService.safeFileName("/etc/passwd")).isEqualTo("passwd");
        assertThat(EvidenceService.safeFileName("a\r\nb.txt")).isEqualTo("ab.txt");
        assertThat(EvidenceService.safeFileName(null)).isEqualTo("upload");
        assertThat(EvidenceService.safeFileName("x".repeat(500))).hasSize(200);
    }

    // ------------------------------------------------------------- F5 compensation (safety rules)

    @Test
    void whenTheLedgerRejectsTheWriteThePinsItMadeAreRemoved() {
        // A JUDGE may not register evidence, so the ledger refuses AFTER both pins were made.
        assertThatThrownBy(() -> service.register(digitalRequest(), file(CONTENT), judge))
                .isInstanceOf(LedgerException.class);

        assertThat(ipfs.pins).as("no orphaned pins").isEmpty();
        assertThat(ipfs.unpinned).hasSize(2);
    }

    @Test
    void aFailedRegistrationNeverUnpinsAFileAnotherRecordStillUses() {
        EvidenceResponse existing = registerDigital();                        // owns the file CID

        assertThatThrownBy(() -> service.register(digitalRequest(), file(CONTENT), judge))
                .isInstanceOf(LedgerException.class);

        assertThat(ipfs.pins).contains(existing.fileCid());                   // still pinned
        assertThat(ipfs.unpinned).doesNotContain(existing.fileCid());
        assertThat(service.verify(existing.evidenceId()).status()).isEqualTo(VerificationStatus.VERIFIED);
    }

    @Test
    void ifTheLedgerCannotBeAskedNothingIsUnpinned() {
        LedgerService broken = mock(LedgerService.class);
        when(broken.createEvidence(any(), any())).thenThrow(new RuntimeException("ledger down"));
        when(broken.findEvidenceIdsByCid(any())).thenThrow(new RuntimeException("ledger down"));

        assertThatThrownBy(() -> build(broken).register(digitalRequest(), file(CONTENT), collector))
                .hasMessage("ledger down");

        assertThat(ipfs.unpinned).as("unsure means keep the data").isEmpty();
        assertThat(ipfs.pins).hasSize(2);
    }

    // ------------------------------------------------------------------------------------ C2

    @Test
    void verifyReportsVerifiedForUntouchedContent() {
        EvidenceResponse r = registerDigital();

        VerificationResponse v = service.verify(r.evidenceId());

        assertThat(v.status()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(v.file().result()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(v.file().actualSha256()).isEqualTo(v.file().expectedSha256());
        assertThat(v.metadata().result()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(v.ledgerVersion()).isEqualTo(1);
    }

    @Test
    void verifyCatchesACorruptedFile() {
        EvidenceResponse r = registerDigital();
        byte[] corrupted = CONTENT.clone();
        corrupted[0] ^= 0x01;                                                   // flip one bit
        ipfs.corrupt(r.fileCid(), corrupted);

        VerificationResponse v = service.verify(r.evidenceId());

        assertThat(v.status()).isEqualTo(VerificationStatus.TAMPERED);
        assertThat(v.file().result()).isEqualTo(VerificationStatus.TAMPERED);
        assertThat(v.file().expectedSha256()).isEqualTo(Sha256.hex(CONTENT));
        assertThat(v.file().actualSha256()).isEqualTo(Sha256.hex(corrupted)).isNotEqualTo(v.file().expectedSha256());
        assertThat(v.metadata().result()).as("only the file was touched").isEqualTo(VerificationStatus.VERIFIED);
    }

    @Test
    void verifyCatchesCorruptedMetadata() {
        EvidenceResponse r = registerDigital();
        ipfs.corrupt(r.metadataCid(), "{\"description\":\"forged\"}".getBytes(StandardCharsets.UTF_8));

        VerificationResponse v = service.verify(r.evidenceId());

        assertThat(v.status()).isEqualTo(VerificationStatus.TAMPERED);
        assertThat(v.metadata().result()).isEqualTo(VerificationStatus.TAMPERED);
        assertThat(v.file().result()).isEqualTo(VerificationStatus.VERIFIED);
    }

    @Test
    void verifyReportsNotFoundWhenTheFileIsGone() {
        EvidenceResponse r = registerDigital();
        ipfs.lose(r.fileCid());

        VerificationResponse v = service.verify(r.evidenceId());

        assertThat(v.status()).isEqualTo(VerificationStatus.NOT_FOUND);
        assertThat(v.file().result()).isEqualTo(VerificationStatus.NOT_FOUND);
        assertThat(v.file().actualSha256()).isNull();
    }

    @Test
    void aProvenMismatchOutranksMissingContent() {
        EvidenceResponse r = registerDigital();
        ipfs.lose(r.fileCid());
        ipfs.corrupt(r.metadataCid(), "x".getBytes(StandardCharsets.UTF_8));

        assertThat(service.verify(r.evidenceId()).status()).isEqualTo(VerificationStatus.TAMPERED);
    }

    @Test
    void anUnreachableNodeIsAnErrorNotANotFoundVerdict() {
        EvidenceResponse r = registerDigital();
        ipfs.down = true;

        assertThatThrownBy(() -> service.verify(r.evidenceId())).isInstanceOf(StorageUnavailableException.class);
    }

    @Test
    void verifyForUnknownEvidenceIs404() {
        assertThatThrownBy(() -> service.verify("EV-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---------------------------------------------------------------------------------- B3

    @Test
    void getWithVerifyTrueRunsTheCheck() {
        EvidenceResponse r = registerDigital();
        ipfs.corrupt(r.fileCid(), "other".getBytes(StandardCharsets.UTF_8));

        assertThat(service.get(r.evidenceId(), false).verification().status()).isEqualTo(VerificationStatus.NOT_CHECKED);
        assertThat(service.get(r.evidenceId(), true).verification().status()).isEqualTo(VerificationStatus.TAMPERED);
    }

    @Test
    void ledgerInformationSurvivesAnIpfsOutage() {
        EvidenceResponse r = registerDigital();
        ipfs.down = true;

        EvidenceResponse degraded = service.get(r.evidenceId(), false);

        assertThat(degraded.metadataAvailable()).isFalse();
        assertThat(degraded.metadata()).isNull();
        assertThat(degraded.fileSha256()).isEqualTo(r.fileSha256());          // ledger data intact
        assertThat(degraded.status()).isEqualTo(EvidenceStatus.COLLECTED);
    }

    @Test
    void lookupByCidFindsTheEvidenceViaFileOrMetadataCid() {
        EvidenceResponse r = registerDigital();

        assertThat(service.findByCid(r.fileCid())).extracting(EvidenceResponse::evidenceId).containsExactly(r.evidenceId());
        assertThat(service.findByCid(r.metadataCid())).extracting(EvidenceResponse::evidenceId).containsExactly(r.evidenceId());
        assertThatThrownBy(() -> service.findByCid(FakeIpfsClient.cidOf("nothing".getBytes())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.findByCid("not-a-cid"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_CID"));
    }

    // ---------------------------------------------------------------------------------- B4

    @Test
    void updateCreatesANewVersionAndOldVersionsStayReadable() {
        EvidenceResponse v1 = registerDigital();

        EvidenceResponse v2 = service.update(v1.evidenceId(),
                new UpdateEvidenceRequest(1, "Corrected location", null, "Locker 7", null), analyst);

        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.lastReason()).isEqualTo("Corrected location");
        assertThat(v2.metadata().location()).isEqualTo("Locker 7");
        assertThat(v2.metadata().description()).as("unchanged fields carry over").isEqualTo("Phone image");
        assertThat(v2.metadata().metadataVersion()).isEqualTo(2);
        assertThat(v2.metadata().previousMetadataCid()).isEqualTo(v1.metadataCid());
        assertThat(v2.fileCid()).isEqualTo(v1.fileCid());                     // file is immutable
        assertThat(v2.fileSha256()).isEqualTo(v1.fileSha256());

        EvidenceResponse old = service.getVersion(v1.evidenceId(), 1);
        assertThat(old.version()).isEqualTo(1);
        assertThat(old.metadata().location()).as("nothing was overwritten").isEqualTo("Locker 4");
        assertThat(service.getVersion(v1.evidenceId(), 2).metadata().location()).isEqualTo("Locker 7");
        assertThatThrownBy(() -> service.getVersion(v1.evidenceId(), 3))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(ipfs.store).as("the old metadata document still exists in IPFS").containsKey(v1.metadataCid());
    }

    @Test
    void updateWithAStaleVersionIs409AndWritesNothingToIpfs() {
        EvidenceResponse v1 = registerDigital();
        service.update(v1.evidenceId(), new UpdateEvidenceRequest(1, "first", "new text", null, null), collector);
        int pinsBefore = ipfs.pins.size();

        assertThatThrownBy(() -> service.update(v1.evidenceId(),
                new UpdateEvidenceRequest(1, "second, stale", "other text", null, null), collector))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("VERSION_CONFLICT");
                });
        assertThat(ipfs.pins).hasSize(pinsBefore);
    }

    @Test
    void updateRefusesToBuildOnTamperedMetadata() {
        EvidenceResponse v1 = registerDigital();
        ipfs.corrupt(v1.metadataCid(), "{\"description\":\"forged\"}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.update(v1.evidenceId(),
                new UpdateEvidenceRequest(1, "r", "x", null, null), collector))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INTEGRITY_CHECK_FAILED"));
        assertThat(service.get(v1.evidenceId(), false).version()).as("no new version was written").isEqualTo(1);
    }

    @Test
    void aRoleWithoutWritePermissionCannotUpdate() {
        EvidenceResponse v1 = registerDigital();

        assertThatThrownBy(() -> service.update(v1.evidenceId(),
                new UpdateEvidenceRequest(1, "r", "x", null, null), judge))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(ipfs.pins.size()).as("the metadata pinned for the refused update was cleaned up").isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------- C3

    @Test
    void historyListsEveryVersionWithLedgerTxIdsAndTimestamps() {
        EvidenceResponse v1 = registerDigital();
        service.update(v1.evidenceId(), new UpdateEvidenceRequest(1, "typo", "fixed", null, null), analyst);
        service.requestDisposal(v1.evidenceId(), new DisposalRequestBody(2, "case closed"), prosecutor);

        var h = service.history(v1.evidenceId());

        assertThat(h).extracting(e -> e.version()).containsExactly(1, 2, 3);
        assertThat(h).extracting(e -> e.action()).containsExactly("CREATED", "METADATA_UPDATED", "DISPOSAL_REQUESTED");
        assertThat(h).extracting(e -> e.txId()).allMatch(t -> t.length() == 64).doesNotHaveDuplicates();
        assertThat(h).extracting(e -> e.timestamp()).containsOnly(Instant.parse("2026-03-01T10:00:00Z"));
        assertThat(h.get(1).actorId()).isEqualTo(analyst.userId().toString());
        assertThat(h.get(1).reason()).isEqualTo("typo");
        assertThat(h.get(2).actorRole()).isEqualTo("PROSECUTOR");
    }

    // ---------------------------------------------------------------------------------- B5

    @Test
    void disposalNeedsAReasonAndAJudgeAndKeepsTheRecordAndItsFile() {
        EvidenceResponse v1 = registerDigital();

        EvidenceResponse pending = service.requestDisposal(v1.evidenceId(), new DisposalRequestBody(1, "Case closed"),
                prosecutor);
        assertThat(pending.disposal().state()).isEqualTo("PENDING");
        assertThat(pending.disposal().requestedBy()).isEqualTo(prosecutor.userId().toString());
        assertThat(pending.status()).isEqualTo(EvidenceStatus.COLLECTED);

        assertThatThrownBy(() -> service.approveDisposal(v1.evidenceId(), new DisposalDecisionBody(2, "ok"), collector))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        EvidenceResponse disposed = service.approveDisposal(v1.evidenceId(), new DisposalDecisionBody(2, "Approved"), judge);
        assertThat(disposed.status()).isEqualTo(EvidenceStatus.DISPOSED);
        assertThat(disposed.version()).isEqualTo(3);

        // The record stays on the ledger, stays readable, and its content still verifies.
        assertThat(service.get(v1.evidenceId(), true).verification().status()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(service.history(v1.evidenceId())).hasSize(3);
        assertThat(ipfs.pins).as("disposal does not unpin anything").contains(v1.fileCid(), v1.metadataCid());

        assertThatThrownBy(() -> service.update(v1.evidenceId(), new UpdateEvidenceRequest(3, "r", "x", null, null), collector))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void rejectingDisposalClearsTheRequest() {
        EvidenceResponse v1 = registerDigital();
        service.requestDisposal(v1.evidenceId(), new DisposalRequestBody(1, "r"), collector);

        EvidenceResponse r = service.rejectDisposal(v1.evidenceId(), new DisposalDecisionBody(2, "not yet"), judge);

        assertThat(r.disposal().state()).isEqualTo("NONE");
        assertThat(r.status()).isEqualTo(EvidenceStatus.COLLECTED);
    }
}

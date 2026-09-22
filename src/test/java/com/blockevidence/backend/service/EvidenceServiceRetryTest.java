package com.blockevidence.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.blockevidence.backend.config.UploadProperties;
import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;
import com.blockevidence.backend.dto.RegisterEvidenceRequest;
import com.blockevidence.backend.exception.ApiException;
import com.blockevidence.backend.ledger.LedgerAction;
import com.blockevidence.backend.ledger.LedgerErrorCode;
import com.blockevidence.backend.ledger.LedgerEvidenceRecord;
import com.blockevidence.backend.ledger.LedgerException;
import com.blockevidence.backend.ledger.LedgerNewEvidence;
import com.blockevidence.backend.ledger.LedgerService;
import com.blockevidence.backend.model.CaseFile;
import com.blockevidence.backend.notification.NotificationService;
import com.blockevidence.backend.repository.CaseEvidenceLinkRepository;
import com.blockevidence.backend.repository.CaseFileRepository;
import com.blockevidence.backend.repository.CaseMemberRepository;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.security.Role;
import com.blockevidence.backend.support.FakeContentKeys;
import com.blockevidence.backend.support.FakeIpfsClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * F5 (docs/DECISIONS.md, extends D-024): {@code EvidenceService.createEvidenceWithRetry} - retries ONLY on
 * {@link LedgerErrorCode#CONCURRENT_WRITE_CONFLICT}, never on any other {@link LedgerException}, and gives up
 * after {@code LEDGER_WRITE_RETRY_ATTEMPTS}. The ledger is mocked here specifically so its failure sequence is
 * controllable; {@code EvidenceServiceTest} covers everything else against the real reference ledger.
 */
class EvidenceServiceRetryTest {

    final Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
    final LedgerService ledger = mock(LedgerService.class);
    final FakeIpfsClient ipfs = new FakeIpfsClient();
    final CaseFileRepository cases = mock(CaseFileRepository.class);
    final CaseEvidenceLinkRepository caseLinks = mock(CaseEvidenceLinkRepository.class);
    final CaseMemberRepository caseMembers = mock(CaseMemberRepository.class);
    final NotificationService notifications = mock(NotificationService.class);
    final com.blockevidence.backend.crypto.UserKeyService userKeys = FakeContentKeys.userKeyService(clock);
    final com.blockevidence.backend.crypto.ContentKeyService contentKeys = FakeContentKeys.contentKeyService(userKeys, clock);

    final AuthenticatedUser collector;
    final EvidenceService service;

    EvidenceServiceRetryTest() {
        collector = new AuthenticatedUser(UUID.randomUUID(), "collector@example.org", Role.COLLECTOR);
        userKeys.provision(collector.userId());

        CaseFile existingCase = new CaseFile("ANY-CASE", "t", null, UUID.randomUUID(), UUID.randomUUID(), clock.instant());
        ReflectionTestUtils.setField(existingCase, "id", UUID.randomUUID());
        lenient().when(cases.findByCaseNumberIgnoreCase(anyString())).thenReturn(Optional.of(existingCase));
        lenient().when(caseMembers.findByCaseIdOrderByAddedAtAsc(any())).thenReturn(List.of());

        service = new EvidenceService(ledger, ipfs, new VerificationService(ipfs, clock), JsonMapper.builder().build(),
                new UploadProperties(List.of("text/plain")), cases, caseLinks, caseMembers, notifications, contentKeys, clock);
    }

    RegisterEvidenceRequest physicalRequest() {
        return new RegisterEvidenceRequest("CASE-1", EvidenceType.PHYSICAL, "Knife", null, null, null);
    }

    LedgerException concurrentWriteConflict() {
        return new LedgerException(LedgerErrorCode.CONCURRENT_WRITE_CONFLICT, "concurrent write, retry is safe");
    }

    /** register()'s own final step re-reads the record via get() - a bare mock answers empty/404 otherwise. */
    void stubSuccessfulReadback() {
        lenient().when(ledger.getEvidence(anyString())).thenAnswer(i -> Optional.of(new LedgerEvidenceRecord(
                i.getArgument(0), "CASE-1", EvidenceType.PHYSICAL, EvidenceStatus.COLLECTED, 1, "m", "a".repeat(64),
                null, null, null, collector.userId().toString(), "COLLECTOR", clock.instant(),
                collector.userId().toString(), "COLLECTOR", clock.instant(), LedgerAction.CREATED, "",
                collector.userId().toString(), LedgerEvidenceRecord.Disposal.none(), LedgerEvidenceRecord.Transfer.none())));
    }

    com.blockevidence.backend.ledger.LedgerTxResult txResult() {
        return new com.blockevidence.backend.ledger.LedgerTxResult("a".repeat(64), clock.instant());
    }

    @Test
    void succeedsOnTheSecondAttemptAfterOneConcurrentWriteConflict() {
        stubSuccessfulReadback();
        when(ledger.createEvidence(any(LedgerNewEvidence.class), any()))
                .thenThrow(concurrentWriteConflict()).thenReturn(txResult());

        service.register(physicalRequest(), null, collector);

        verify(ledger, times(2)).createEvidence(any(LedgerNewEvidence.class), any());
    }

    @Test
    void succeedsOnTheThirdAndFinalAttempt() {
        stubSuccessfulReadback();
        when(ledger.createEvidence(any(LedgerNewEvidence.class), any()))
                .thenThrow(concurrentWriteConflict()).thenThrow(concurrentWriteConflict()).thenReturn(txResult());

        service.register(physicalRequest(), null, collector);

        verify(ledger, times(3)).createEvidence(any(LedgerNewEvidence.class), any());
    }

    @Test
    void givesUpAfterExhaustingAllAttemptsAndStillPropagatesTheFailure() {
        doThrow(concurrentWriteConflict()).when(ledger).createEvidence(any(LedgerNewEvidence.class), any());

        assertThatThrownBy(() -> service.register(physicalRequest(), null, collector))
                .isInstanceOfSatisfying(LedgerException.class,
                        e -> assertThat(e.ledgerCode()).isEqualTo(LedgerErrorCode.CONCURRENT_WRITE_CONFLICT));

        verify(ledger, times(3)).createEvidence(any(LedgerNewEvidence.class), any());
    }

    @Test
    void neverRetriesAChaincodeReportedVersionConflict() {
        // Creation carries no expectedVersion, but any other LedgerException must fail on the FIRST attempt -
        // retrying with identical arguments could never turn a non-transient failure into a success.
        doThrow(new LedgerException(LedgerErrorCode.VERSION_CONFLICT, "stale version")).when(ledger)
                .createEvidence(any(LedgerNewEvidence.class), any());

        assertThatThrownBy(() -> service.register(physicalRequest(), null, collector))
                .isInstanceOfSatisfying(LedgerException.class,
                        e -> assertThat(e.ledgerCode()).isEqualTo(LedgerErrorCode.VERSION_CONFLICT));

        verify(ledger, times(1)).createEvidence(any(LedgerNewEvidence.class), any());
    }

    @Test
    void neverRetriesAnUnrelatedApiException() {
        doThrow(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "bad")).when(ledger)
                .createEvidence(any(LedgerNewEvidence.class), any());

        assertThatThrownBy(() -> service.register(physicalRequest(), null, collector)).isInstanceOf(ApiException.class);

        verify(ledger, times(1)).createEvidence(any(LedgerNewEvidence.class), any());
    }

    @Test
    void exhaustingRetriesStillUnpinsCompensatedContent() {
        // PHYSICAL evidence has no file, but the metadata document IS pinned before the ledger call; exhausting
        // retries must still run compensate() exactly as an immediate, non-retried failure would (D-024 unchanged).
        doThrow(concurrentWriteConflict()).when(ledger).createEvidence(any(LedgerNewEvidence.class), any());
        lenient().when(ledger.findEvidenceIdsByCid(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.register(physicalRequest(), null, collector)).isInstanceOf(LedgerException.class);

        assertThat(ipfs.pins).as("the pinned metadata document was unpinned by compensate()").isEmpty();
        assertThat(ipfs.unpinned).hasSize(1);
    }
}

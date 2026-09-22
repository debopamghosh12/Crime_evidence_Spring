package com.blockevidence.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.blockevidence.backend.domain.CaseRole;
import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;
import com.blockevidence.backend.ledger.LedgerAction;
import com.blockevidence.backend.ledger.LedgerEvidenceEvent;
import com.blockevidence.backend.ledger.LedgerEvidenceRecord;
import com.blockevidence.backend.model.CaseFile;
import com.blockevidence.backend.model.CaseMember;
import com.blockevidence.backend.repository.CaseFileRepository;
import com.blockevidence.backend.repository.CaseMemberRepository;
import com.blockevidence.backend.security.Role;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** H4 (design docs/G3_SYNC_DESIGN.md section 11): which actions notify whom. */
class NotificationServiceTest {

    final Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
    final NotificationRepository notifications = mock(NotificationRepository.class);
    final CaseFileRepository cases = mock(CaseFileRepository.class);
    final CaseMemberRepository members = mock(CaseMemberRepository.class);
    final NotificationService service = new NotificationService(notifications, cases, members, clock);

    final UUID receiverId = UUID.randomUUID();
    final UUID caseUuid = UUID.randomUUID();
    final UUID member1 = UUID.randomUUID();
    final UUID member2 = UUID.randomUUID();

    LedgerEvidenceRecord record(LedgerAction action, EvidenceStatus status, String transferTo) {
        var transfer = transferTo == null ? LedgerEvidenceRecord.Transfer.none()
                : new LedgerEvidenceRecord.Transfer(LedgerEvidenceRecord.TransferState.PENDING, "sender-1",
                        transferTo, Role.FORENSIC_ANALYST, "reason", null, clock.instant(), null, null);
        return new LedgerEvidenceRecord("EV-1", "CASE-1", EvidenceType.PHYSICAL, status, 2, "m", "a".repeat(64),
                null, null, null, "creator-1", "COLLECTOR", clock.instant(), "actor-1", "COLLECTOR", clock.instant(),
                action, "reason", "custodian-1", LedgerEvidenceRecord.Disposal.none(), transfer);
    }

    void caseExistsWithMembers(UUID... memberIds) {
        CaseFile file = new CaseFile("CASE-1", "t", null, UUID.randomUUID(), UUID.randomUUID(), clock.instant());
        ReflectionTestUtils.setField(file, "id", caseUuid);
        when(cases.findByCaseNumberIgnoreCase("CASE-1")).thenReturn(Optional.of(file));
        List<CaseMember> rows = List.of(memberIds).stream()
                .map(id -> new CaseMember(caseUuid, id, CaseRole.INVESTIGATOR, UUID.randomUUID(), clock.instant()))
                .toList();
        when(members.findByCaseIdOrderByAddedAtAsc(caseUuid)).thenReturn(rows);
    }

    @Test
    void transferInitiatedNotifiesOnlyTheReceiver() {
        LedgerEvidenceEvent event = new LedgerEvidenceEvent("EV-1", 2, "tx-1", 1L, LedgerAction.TRANSFER_INITIATED);
        service.notifyForEvent(event, record(LedgerAction.TRANSFER_INITIATED, EvidenceStatus.COLLECTED,
                receiverId.toString()));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(receiverId);
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.TRANSFER_PENDING);
        verify(cases, never()).findByCaseNumberIgnoreCase(any()); // no case lookup needed for this one
    }

    @Test
    void statusChangedNotifiesEveryCaseMember() {
        caseExistsWithMembers(member1, member2);
        LedgerEvidenceEvent event = new LedgerEvidenceEvent("EV-1", 2, "tx-1", 1L, LedgerAction.STATUS_CHANGED);

        service.notifyForEvent(event, record(LedgerAction.STATUS_CHANGED, EvidenceStatus.ANALYZED, null));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Notification::getUserId).containsExactlyInAnyOrder(member1, member2);
        assertThat(captor.getAllValues()).extracting(Notification::getType).containsOnly(NotificationType.STATUS_CHANGED);
    }

    @Test
    void aCaseIdThatNamesNoRealCaseIsSkippedSilentlyNotThrown() {
        when(cases.findByCaseNumberIgnoreCase("CASE-1")).thenReturn(Optional.empty());
        LedgerEvidenceEvent event = new LedgerEvidenceEvent("EV-1", 2, "tx-1", 1L, LedgerAction.DISPOSAL_APPROVED);

        service.notifyForEvent(event, record(LedgerAction.DISPOSAL_APPROVED, EvidenceStatus.DISPOSED, null));

        verify(notifications, never()).save(any());
    }

    @Test
    void unmappedActionsCreateNoNotification() {
        for (LedgerAction action : List.of(LedgerAction.CREATED, LedgerAction.METADATA_UPDATED,
                LedgerAction.TRANSFER_ACCEPTED, LedgerAction.TRANSFER_REJECTED, LedgerAction.TRANSFER_CANCELLED)) {
            service.notifyForEvent(new LedgerEvidenceEvent("EV-1", 2, "tx-1", 1L, action),
                    record(action, EvidenceStatus.COLLECTED, null));
        }
        verify(notifications, never()).save(any());
    }

    @Test
    void aNotificationServiceFailureNeverPropagatesToTheCaller() {
        when(cases.findByCaseNumberIgnoreCase(any())).thenThrow(new RuntimeException("db hiccup"));
        LedgerEvidenceEvent event = new LedgerEvidenceEvent("EV-1", 2, "tx-1", 1L, LedgerAction.STATUS_CHANGED);

        service.notifyForEvent(event, record(LedgerAction.STATUS_CHANGED, EvidenceStatus.ANALYZED, null)); // must not throw
    }

    @Test
    void tamperAlertGoesToTheCurrentCustodianAndIsSkippedWithNoCustodian() {
        service.notifyTamperAlert("EV-1", "CASE-1", receiverId.toString());
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.TAMPER_ALERT);
        assertThat(captor.getValue().getUserId()).isEqualTo(receiverId);

        service.notifyTamperAlert("EV-2", "CASE-1", null);
        verify(notifications, org.mockito.Mockito.times(1)).save(any()); // no second save
    }
}

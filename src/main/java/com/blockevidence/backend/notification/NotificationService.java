package com.blockevidence.backend.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.blockevidence.backend.dto.PageResponse;
import com.blockevidence.backend.ledger.LedgerAction;
import com.blockevidence.backend.ledger.LedgerEvidenceEvent;
import com.blockevidence.backend.ledger.LedgerEvidenceRecord;
import com.blockevidence.backend.model.CaseFile;
import com.blockevidence.backend.repository.CaseFileRepository;
import com.blockevidence.backend.repository.CaseMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * H4 (design docs/G3_SYNC_DESIGN.md section 11). {@link #notifyForEvent} is called from
 * {@code sync.EventProcessor} inside the SAME transaction as the activity/projection/checkpoint write (design
 * section 5): if that transaction rolls back, no orphaned notification is left behind either. A tamper alert is
 * NOT a ledger event and does not go through this method - {@link #notifyTamperAlert} is called directly by
 * EvidenceService, independent of G3.
 *
 * <p>Never fails the caller: a lookup problem (e.g. the case does not exist off-chain, common for evidence
 * registered under a caseId that names no real case) is logged and skipped, not thrown - a missing notification
 * must never be allowed to break evidence sync or a verify call.
 */
@Service
public class NotificationService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final CaseFileRepository cases;
    private final CaseMemberRepository members;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, CaseFileRepository cases,
            CaseMemberRepository members, Clock clock) {
        this.notifications = notifications;
        this.cases = cases;
        this.members = members;
        this.clock = clock;
    }

    @Transactional
    public void notifyForEvent(LedgerEvidenceEvent event, LedgerEvidenceRecord record) {
        try {
            if (event.action() == LedgerAction.TRANSFER_INITIATED) {
                notifyTransferReceiver(record);
            } else if (mapsToCaseNotification(event.action())) {
                notifyCaseMembers(record, typeFor(event.action()),
                        "Evidence " + record.evidenceId() + " " + describeAction(event.action(), record));
            }
        } catch (RuntimeException e) {
            log.warn("Could not create a notification for {} on {}: {}", event.action(), event.evidenceId(),
                    e.toString());
        }
    }

    @Transactional
    public void notifyTamperAlert(String evidenceId, String caseId, String currentCustodian) {
        if (currentCustodian == null) {
            return;
        }
        try {
            save(UUID.fromString(currentCustodian), NotificationType.TAMPER_ALERT, evidenceId, caseId,
                    "Verification found evidence " + evidenceId + " does not match its recorded hash (TAMPERED)");
        } catch (RuntimeException e) {
            log.warn("Could not create a tamper alert for {}: {}", evidenceId, e.toString());
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<Notification> list(UUID userId, int page, int size) {
        var result = notifications.findByUserIdOrderByCreatedAtDesc(userId,
                PageRequest.of(page, Math.min(Math.max(size, 1), 200)));
        return PageResponse.of(result);
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        notifications.findByIdAndUserId(notificationId, userId).ifPresent(n -> n.markRead(clock.instant()));
    }

    private void notifyTransferReceiver(LedgerEvidenceRecord record) {
        var transfer = record.transfer();
        if (transfer == null || transfer.to() == null) {
            return;
        }
        save(UUID.fromString(transfer.to()), NotificationType.TRANSFER_PENDING, record.evidenceId(), record.caseId(),
                "You have a pending custody transfer for evidence " + record.evidenceId());
    }

    private void notifyCaseMembers(LedgerEvidenceRecord record, NotificationType type, String message) {
        if (record.caseId() == null) {
            return;
        }
        CaseFile caseFile = cases.findByCaseNumberIgnoreCase(record.caseId()).orElse(null);
        if (caseFile == null) {
            return; // caseId names no real case (E3 does not require one for pre-E3 records) - nobody to notify
        }
        List<UUID> recipients = members.findByCaseIdOrderByAddedAtAsc(caseFile.getId()).stream()
                .map(com.blockevidence.backend.model.CaseMember::getUserId).distinct().toList();
        for (UUID userId : recipients) {
            save(userId, type, record.evidenceId(), record.caseId(), message);
        }
    }

    private void save(UUID userId, NotificationType type, String evidenceId, String caseId, String message) {
        notifications.save(new Notification(UUID.randomUUID(), userId, type, evidenceId, caseId, message,
                clock.instant()));
    }

    private static boolean mapsToCaseNotification(LedgerAction action) {
        return switch (action) {
            case STATUS_CHANGED, DISPOSAL_REQUESTED, DISPOSAL_APPROVED, DISPOSAL_REJECTED -> true;
            default -> false;
        };
    }

    private static NotificationType typeFor(LedgerAction action) {
        return switch (action) {
            case STATUS_CHANGED -> NotificationType.STATUS_CHANGED;
            case DISPOSAL_REQUESTED -> NotificationType.DISPOSAL_REQUESTED;
            case DISPOSAL_APPROVED -> NotificationType.DISPOSAL_APPROVED;
            case DISPOSAL_REJECTED -> NotificationType.DISPOSAL_REJECTED;
            default -> throw new IllegalArgumentException("no notification type for " + action);
        };
    }

    private static String describeAction(LedgerAction action, LedgerEvidenceRecord record) {
        return switch (action) {
            case STATUS_CHANGED -> "changed status to " + record.status();
            case DISPOSAL_REQUESTED -> "has a disposal request pending";
            case DISPOSAL_APPROVED -> "was disposed";
            case DISPOSAL_REJECTED -> "had its disposal request rejected";
            default -> action.name();
        };
    }
}

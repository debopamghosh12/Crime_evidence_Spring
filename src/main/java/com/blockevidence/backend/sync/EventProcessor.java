package com.blockevidence.backend.sync;

import java.time.Clock;
import java.util.UUID;

import com.blockevidence.backend.crypto.ContentKeyService;
import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.ledger.LedgerAction;
import com.blockevidence.backend.ledger.LedgerEvidenceEvent;
import com.blockevidence.backend.ledger.LedgerEvidenceRecord;
import com.blockevidence.backend.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * G3 (design docs/G3_SYNC_DESIGN.md section 5): the atomic unit of work for ONE enriched event. Everything in
 * {@link #apply} runs in one database transaction: either the activity row, the projection upsert and the
 * checkpoint advance all commit together, or none of them do (design section 5 step 3). The caller
 * ({@link EventSyncListener}) does the ledger enrichment read BEFORE calling this, outside any transaction.
 */
@Service
public class EventProcessor {

    private final EvidenceActivityRepository activities;
    private final EvidenceProjectionRepository projections;
    private final LedgerSyncCheckpointRepository checkpoints;
    private final NotificationService notifications;
    private final ContentKeyService contentKeys;
    private final Clock clock;

    public EventProcessor(EvidenceActivityRepository activities, EvidenceProjectionRepository projections,
            LedgerSyncCheckpointRepository checkpoints, NotificationService notifications, ContentKeyService contentKeys,
            Clock clock) {
        this.activities = activities;
        this.projections = projections;
        this.checkpoints = checkpoints;
        this.notifications = notifications;
        this.contentKeys = contentKeys;
        this.clock = clock;
    }

    /**
     * @return true if this event was genuinely new and applied; false if it was already processed (a redelivery,
     *         design section 5 step 3a) - the caller does not need to distinguish these for correctness, only for
     *         logging, since both outcomes leave the read model exactly where it should be.
     */
    @Transactional
    public boolean apply(LedgerEvidenceEvent event, LedgerEvidenceRecord record) {
        int inserted = activities.insertIfAbsent(UUID.randomUUID(), event.txId(), event.blockNumber(),
                event.evidenceId(), event.version(), event.action().name(), record.caseId(),
                statusName(record.status()), record.updatedBy(), record.updatedByRole(), record.lastReason(),
                record.updatedAt(), clock.instant());
        if (inserted == 0) {
            return false; // already processed in an earlier run; the checkpoint was already advanced past it too
        }
        projections.upsert(record.evidenceId(), record.caseId(), record.evidenceType().name(),
                record.status().name(), record.version(), record.currentCustodian(), record.createdBy(),
                record.createdAt(), record.updatedAt(), record.lastAction().name(), record.lastReason());
        checkpoints.advance(event.blockNumber(), event.txId(), clock.instant());
        notifications.notifyForEvent(event, record); // H4; never throws, see NotificationService
        wrapForTransferReceiver(event, record); // F3 Q2; never throws, see ContentKeyService.reWrapForNewUser
        return true;
    }

    /**
     * F2/F3 Q2 (design section 5, owner-approved): a custody transfer's receiver is treated the same as a newly
     * added case member - re-wrap the content key for them the moment TRANSFER_INITIATED is processed, whether
     * or not they are (yet) a formal case member. Same async timing as H4's TRANSFER_PENDING notification: the
     * receiver's wrapped copy exists once G3 has processed this event, not synchronously with the initiate call.
     */
    private void wrapForTransferReceiver(LedgerEvidenceEvent event, LedgerEvidenceRecord record) {
        if (event.action() != LedgerAction.TRANSFER_INITIATED) {
            return;
        }
        var transfer = record.transfer();
        if (transfer == null || transfer.to() == null) {
            return;
        }
        contentKeys.reWrapForNewUser(record.evidenceId(), UUID.fromString(transfer.to()));
    }

    private static String statusName(EvidenceStatus status) {
        return status == null ? null : status.name();
    }
}

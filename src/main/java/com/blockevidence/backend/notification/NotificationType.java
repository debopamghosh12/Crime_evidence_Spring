package com.blockevidence.backend.notification;

/** H4. TAMPER_ALERT is not a ledger event (G3 never sees it): it is raised directly by VerificationService
 *  when a verify call finds TAMPERED, independent of the event sync pipeline. */
public enum NotificationType {
    TRANSFER_PENDING,
    STATUS_CHANGED,
    DISPOSAL_REQUESTED,
    DISPOSAL_APPROVED,
    DISPOSAL_REJECTED,
    TAMPER_ALERT
}

package com.blockevidence.backend.sync;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * G3 (design section 7): the single durable row recording how far event sync has got. Exactly one row, id fixed
 * to 1 by the table's own CHECK constraint. {@code blockNumber}/{@code txId} are null until the first event is
 * ever processed, meaning "start from block 0" (design section 6).
 */
@Entity
@Table(name = "ledger_sync_checkpoint")
public class LedgerSyncCheckpoint {

    @Id
    private short id = 1;

    @Column(name = "block_number")
    private Long blockNumber;

    @Column(name = "tx_id")
    private String txId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LedgerSyncCheckpoint() {
        // required by JPA
    }

    public Long getBlockNumber() {
        return blockNumber;
    }

    public String getTxId() {
        return txId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

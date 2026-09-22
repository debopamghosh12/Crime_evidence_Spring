package com.blockevidence.backend.sync;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerSyncCheckpointRepository extends JpaRepository<LedgerSyncCheckpoint, Short> {

    /** G3 (design section 5 step 3c). Always exactly one row (id=1, inserted by the V4 migration). */
    @Modifying
    @Query("update LedgerSyncCheckpoint c set c.blockNumber = :blockNumber, c.txId = :txId, c.updatedAt = :updatedAt where c.id = 1")
    void advance(@Param("blockNumber") long blockNumber, @Param("txId") String txId, @Param("updatedAt") Instant updatedAt);
}

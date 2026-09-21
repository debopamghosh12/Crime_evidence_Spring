# C3: Ledger history

**FEATURE_LIST:** C3 (P0, new). **Phase:** 2. **Status:** Spring side done and verified live against the
in-memory reference ledger. The real source (`GetHistoryForKey`) is Fabric and is part of G2.

## Scoping
"Full version history with transaction IDs and timestamps." Fabric's `GetHistoryForKey` returns every
committed value of a key with its tx id and timestamp, which makes "older versions stay readable" (B4) free of
extra storage: the history *is* the version store.

## What was built
- `LedgerService.getHistory(id)` (final signature, D-016): a list of `LedgerHistoryEntry(txId, timestamp,
  record)`, oldest first, each carrying the **full record as of that write**; unknown id -> `EVIDENCE_NOT_FOUND`.
- `GET /api/evidence/{id}/history` -> `HistoryEntryResponse`: `version, txId, timestamp, action, status,
  metadataCid, actorId, actorRole, reason`. Every role may read it.
- `GET /api/evidence/{id}/versions/{n}` is built on the same call.
- Every write records who did it (id and role, from the token) and why, so the history doubles as the audit
  trail for B4 and B5.

## Decisions
- One `version` counter increments on **every** successful write (create, metadata update, status change,
  disposal request, approval, rejection), so history entries and versions correspond 1:1.
- Timestamps come from the ledger transaction (C4), never the server clock. In the reference ledger this is the
  injected clock, standing in for Fabric's tx timestamp.

## Verification (TEST_CHECKLIST.md P2.2)
```
v1  CREATED           tx=6f5bc362b6e045bb..  at=2026-09-21T19:16:02.325103900Z  by=COLLECTOR         reason=''
v2  METADATA_UPDATED  tx=6b0f2ee5d6a2dd67..  at=2026-09-21T19:16:02.490837200Z  by=FORENSIC_ANALYST  reason='Location corrected after audit'
```
(run 2 values shown; run 3 identical in shape.) After disposal: `[(1,'CREATED'),(2,'DISPOSAL_REQUESTED'),(3,'DISPOSAL_APPROVED')]`.
Automated: `InMemoryLedgerServiceTest.historyIsAppendOnlyOldestFirstWithDistinctTxIds`, `EvidenceServiceTest.historyListsEveryVersion...`.

## Known limits
- **The tx ids above are random 64-hex values from the in-memory ledger, not Fabric transaction ids.** They
  become real ones only after G2 is implemented.
- Not paginated; fine for the size of an evidence record's life, revisit if a record accumulates thousands of writes.

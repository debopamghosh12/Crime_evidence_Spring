# D3: Custody timeline (chain of custody)

**FEATURE_LIST:** D3. **Phase:** 3. **Status:** done, verified live through Spring -> real Fabric.

## What was built
`GET /api/evidence/{id}/chain-of-custody` (any authenticated role, like other reads). Built entirely from the ledger's own history
(`LedgerService.getHistory`, the peer's history index), so it cannot disagree with the ledger. Nothing is stored in PostgreSQL.

Each event: `version`, `type` (`CUSTODY_STARTED` for the registration, `TRANSFER_INITIATED/ACCEPTED/REJECTED/CANCELLED`), `fromUser`, `toUser`,
`custodianAfter`, `reason`, `resolutionNote`, the ledger `timestamp`, and the **ledger `txId`** of that step. Status changes, metadata updates
and disposal steps are deliberately **not** custody events. Also returned: the current custodian and any pending transfer.

## Verification (TEST_CHECKLIST P3-L, D3 section; user ids shortened)
```
currentCustodian: b9481c66.. | pendingTransfer: None
v1  CUSTODY_STARTED     from=-          to=f47ff616.. after=f47ff616.. tx=9554dd7508af..
v2  TRANSFER_INITIATED  from=f47ff616.. to=b9481c66.. after=f47ff616.. tx=aac472479d3e..  reason='forensic analysis'
v3  TRANSFER_ACCEPTED   from=f47ff616.. to=b9481c66.. after=b9481c66.. tx=9828eb95e63d..  note='received intact, seal verified'
v4  TRANSFER_INITIATED  ...   v5  TRANSFER_REJECTED ...   v6  TRANSFER_INITIATED ...   v7  TRANSFER_CANCELLED ...
```
Every step has a distinct txId. An item that never moved shows `['CUSTODY_STARTED']` only; an unknown id is 404 `EVIDENCE_NOT_FOUND`;
an item written before transfers existed (chaincode v1.1) still reads (also pinned by the real v1.1 fixture, see
docs/bugs/ledger-record-without-transfer-key.md). `DELETE` on the routes -> 405.

## Known limits
- The events carry opaque user ids; turning them into names needs the user-directory endpoint that does not exist yet.
- Whether each `txId` is a real committed transaction was proven for Phase 2 writes (qscc, TEST_CHECKLIST P2-F-E F1); the Phase 3
  steps use the same mechanism but were not each looked up in qscc.

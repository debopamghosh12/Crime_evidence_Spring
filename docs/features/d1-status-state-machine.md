# D1: Evidence status state machine

**FEATURE_LIST:** D1. **Phase:** 3. **Status:** done, verified live through Spring -> real Fabric (chaincode v1.2 seq 3).

## Scoping
Status must move only along legal steps, by authorised roles, with a reason, and never by editing history. Phase 2 already enforced
"DISPOSED only through an approved disposal" and "a DISPOSED record is frozen" (B5); D1 finalises the rest, which was provisional.

## What was built
- `POST /api/evidence/{id}/status` `{expectedVersion, status, reason}`. Roles: COLLECTOR, FORENSIC_ANALYST, PROSECUTOR (D-019).
- Legal moves (single source: `EvidenceStatus.canMoveTo`, mirrored in `chaincode/evidence/model.go`, both tested):
  `COLLECTED -> PROCESSING -> ANALYZED -> ARCHIVED -> RELEASED`; no backward moves, no skipping. `DISPOSED` is not reachable here.
- The service checks the move **before** calling the ledger (a clear 409 with no wasted transaction); the chaincode checks it again
  independently, so the rule holds even for a caller that bypasses Spring.
- Every change is a new record version with `lastAction = STATUS_CHANGED`, the reason, the actor from the token (C-05), and a ledger
  txId/timestamp visible in `/history`.

## Verification (TEST_CHECKLIST P3-L, D1 section)
```
COLLECTED -> PROCESSING (analyst) 200      PROCESSING -> ANALYZED 200
ANALYZED -> RELEASED (skips ARCHIVED)  409 INVALID_STATE   ANALYZED -> COLLECTED (backwards)  409 INVALID_STATE
status DISPOSED directly               400 INVALID_ARGUMENT "DISPOSED is only reachable through an approved disposal (B5)"
stale expectedVersion                  409 VERSION_CONFLICT   JUDGE changes status  403 ACCESS_DENIED   blank reason  400 VALIDATION_FAILED
ANALYZED -> ARCHIVED (prosecutor)      200 (version 4)
```
Automated: `LifecycleServicesTest`, `Phase3ControllersTest` (role matrix), `InMemoryLedgerServiceTest`, Go `evidence_test.go`.

## Known limits
- **Any of the three roles may move any evidence's status; it need not be the custodian.** Tightening this (custodian-only, or per-step
  roles such as "only an analyst marks ANALYZED") is an owner decision, not made. Listed in `docs/KNOWN_GAPS.md` D.
- The role is trusted from the backend until A2 (C-08).

# B5: Archive / dispose instead of delete

**FEATURE_LIST:** B5 (P0, changed from prototype). **Phase:** 2. **Status:** done, verified live against the
in-memory reference ledger. Constraint **C-02** lives here.

## Scoping
"Disposal needs a reason and approval from an authorised role. The record stays on the ledger. Replaces the
DELETE and bulk DELETE endpoints." So: there must be no delete at all, and a two-person approval flow in its
place. "Authorised role" was undefined, so the permission matrix (D-019) is a proposal for the owner to confirm.

## What was built
- `POST /api/evidence/{id}/disposal` `{expectedVersion, reason}`: **request**. COLLECTOR or PROSECUTOR.
  Record gains `disposal.state = PENDING`; status is unchanged.
- `POST /api/evidence/{id}/disposal/approve` and `/reject` `{expectedVersion, note}`: **JUDGE only**.
  Approve sets status **DISPOSED**; reject clears the request.
- **No DELETE mapping exists**, for a single item or in bulk. A `DELETE` anywhere under `/api/evidence`
  answers 405. `LedgerService` has no delete-like method (a test asserts it).
- DISPOSED freezes the record: no update, no new disposal, no status change. It stays readable, listed in
  history, and verifiable.
- **Nothing is unpinned from IPFS** at any stage (D-027): disposal is a ledger state change.

## Design points
- **Approval pins the version.** The judge sends the `expectedVersion` they reviewed, so a record edited after
  the request cannot be approved unseen (live: approving version 1 of a record at version 2 -> 409).
- ADMIN and AUDITOR hold no write permission (A4). Requesting is allowed from any non-DISPOSED status; the
  judge is the safeguard (tighten with D1).

## Verification (TEST_CHECKLIST.md P2.2)
```
PROSECUTOR requests   200  disposal.state PENDING  status COLLECTED  lastAction DISPOSAL_REQUESTED
COLLECTOR approves    403 ACCESS_DENIED
JUDGE approves v1     409 VERSION_CONFLICT             (record is at v2)
JUDGE approves v2     200  status DISPOSED  version 3  lastAction DISPOSAL_APPROVED  lastReason "Order verified"
edit after DISPOSED   409 INVALID_STATE "Evidence is DISPOSED and can no longer change"
GET / history after   200  status DISPOSED;  history [(1,CREATED),(2,DISPOSAL_REQUESTED),(3,DISPOSAL_APPROVED)]
DELETE as collector, admin, judge   405 METHOD_NOT_ALLOWED  (x3)
```
Automated: `EvidenceControllerTest` (request/decide role matrix over all six roles; three DELETE shapes -> 405 with
the service never called), `InMemoryLedgerServiceTest` (state rules), `EvidenceServiceTest` (disposal keeps file
and verification; nothing unpinned; reject clears).

## Known limits
- The "approver is not the requester" rule exists in the ledger, but the role sets are disjoint, so it cannot
  trigger yet; it matters once roles overlap.
- The physical purge of a digital file is deliberately out of scope.
- ~~Not yet on Fabric.~~ (Superseded 2026-09-22, see the update below.)

## Update 2026-09-22: on real Fabric
The chaincode enforces disposal itself, independently of Spring: driven directly with the peer CLI it refused a COLLECTOR approving, a JUDGE approving a stale version, DISPOSED set through `UpdateStatus`, and every write after DISPOSED; `DeleteEvidence` answers `Function DeleteEvidence not found in contract EvidenceContract`. The DISPOSED record and its five-entry history remain readable on the ledger (P2-F.4). The role check is constraint C-08: the chaincode trusts the role argument.

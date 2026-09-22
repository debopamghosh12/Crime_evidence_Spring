# H1-H4: search, dashboard, activity feed, notifications

**FEATURE_LIST:** H1 (P0), H2 (P1), H3 (P1), H4 (P1). **Phase:** 4. **Status:** done, verified live against real
Fabric + PostgreSQL, built straight through after G3's approval (docs/G3_SYNC_DESIGN.md section 11).

## What was built
All four read exclusively from G3's off-chain schema (`evidence_projection`, `evidence_activity`) - none of them
call `LedgerService`, which is the entire point of having G3 sync in the first place.

- **H1 search/filter:** `GET /api/evidence/search?caseId=&status=&type=&officer=&from=&to=&q=&page=&size=&sort=`.
  A JPA Specification per filter (`EvidenceProjectionSpecifications`), combined with AND; `officer` matches
  either the creator or the current custodian; `q` is a case-insensitive `ILIKE` over the case number and the
  CURRENT `lastReason` (see the limit below); `sort` is an allow-list (`createdAt`/`updatedAt`/`status`/`caseId`),
  never a raw pass-through. Paginated (`PageResponse`, capped at 200/page).
- **H2 dashboard:** `GET /api/dashboard`. Counts by status and type (native `GROUP BY` over `evidence_projection`),
  the top 20 cases by evidence count, and activity over the last 30 days grouped by calendar day (native
  `date_trunc('day', ...)` over `evidence_activity`).
- **H3 activity feed:** `GET /api/activity?caseId=&page=&size=`. `evidence_activity` ordered by `ledger_at DESC`;
  `caseId` narrows the result, it is not an access restriction (no case-level read restriction exists anywhere in
  this project yet, A5).
- **H4 notifications:** `GET /api/notifications`, `POST /api/notifications/{id}/read` - always the caller's own,
  from the token (C-05), never another user's. Populated two ways: (a) `NotificationService.notifyForEvent`,
  called from `sync.EventProcessor` in the SAME transaction as the G3 read-model write - `TRANSFER_INITIATED`
  notifies the named receiver, `STATUS_CHANGED`/`DISPOSAL_REQUESTED`/`DISPOSAL_APPROVED`/`DISPOSAL_REJECTED`
  notify every case member; (b) `notifyTamperAlert`, called directly by `EvidenceService` the instant a verify
  call finds TAMPERED - not a ledger event, independent of G3 entirely. In-app only; email is explicitly out of
  scope (no mail server configured, so not trivial), per the owner's instruction. A lookup failure (e.g. a caseId
  that names no real case) is logged and skipped, never thrown - a notification problem must never break evidence
  sync or a verify call.

## Verification (TEST_CHECKLIST P4-H)
A case with two evidence items; a custody transfer on one; a status change, disposal request and disposal
approval on the other; then, live:
```
H1: caseId filter -> exactly the 2 items; status=DISPOSED -> includes the disposed one (among others from
    earlier phases' test data); free-text q=lab -> EMPTY (see the known limit below); paginated size=1 -> 1 item,
    totalPages 2
H2: totalEvidence 103; byStatus {ARCHIVED:3, COLLECTED:91, DISPOSED:9}; byType {PHYSICAL:78, DIGITAL:25};
    activityByDay: 2 entries, last day count 85
H3: 6 events for the case, newest first: DISPOSAL_APPROVED, DISPOSAL_REQUESTED, STATUS_CHANGED,
    TRANSFER_INITIATED, CREATED, CREATED
H4: the transfer receiver got TRANSFER_PENDING; both case members (lead + one added member) got
    DISPOSAL_APPROVED/DISPOSAL_REQUESTED/STATUS_CHANGED; two non-members got nothing; mark-read set readAt;
    a real corrupted IPFS block + verify=true -> TAMPERED -> the current custodian got TAMPER_ALERT
```
Automated: `NotificationServiceTest` (6: transfer notifies only the receiver, status/disposal notify every case
member, an unmapped action creates nothing, a lookup failure never propagates, a missing case is skipped, tamper
alerts go to the custodian and are skipped with none). H1/H2/H3 are read-only, no-business-logic queries verified
directly against real PostgreSQL live rather than duplicated in a unit test with a fake database.

## Known limits
- **H1's free-text search matches the CURRENT `lastReason` only**, not any earlier version's reason (found live,
  DECISIONS D-054): `evidence_projection` is a current-state table by design, so a word from a superseded reason
  correctly returns nothing. Searching history text would need to query `evidence_activity` (H3) instead; not
  built, since nothing asked for it.
- No full-text index infrastructure (a plain `ILIKE`) at this project's scale.
- H3 is not filtered to the requesting user's cases (A5 is still not built, same as every other read here).
- H4 email notifications are not built (explicitly out of scope per the owner).

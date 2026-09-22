# E3: Link evidence to cases

**FEATURE_LIST:** E3 ("Evidence belongs to a real case entity (today caseId is only a string). Case summary shows all linked
evidence." JPA relation, ledger caseId). **Phase:** 3. **Status:** done, verified live against real PostgreSQL, real Fabric
(chaincode v1.2 seq 3), and as the Phase 2 checklist's regression.

## Decision: Option B (owner-approved), not Option A
Two designs were laid out (docs/HANDOVER.md session 5): a chaincode composite-key index (a new chaincode version), or a
PostgreSQL link table maintained by `EvidenceService.register`. **The owner chose Option B**, explicitly to keep case
validation in PostgreSQL, consistent with how E1/E2 already treat the ledger as seeing only the bare case number (C-06). No
chaincode change was needed for E3.

## What was built
- Table `case_evidence` (Flyway `V3__case_evidence_links.sql`, a new migration; V1/V2 untouched): `case_id` (FK to `cases`),
  `evidence_id` (the ledger's `EV-...` id, **unique** — one evidence item names exactly one case, mirroring the ledger
  record's own single `caseId` field), `linked_by`, `linked_at`. Immutable: no update or delete method.
- **`EvidenceService.register` now requires the caseId to name a real, existing case** (case-insensitive, the same match E1
  uses for uniqueness): `cases.findByCaseNumberIgnoreCase(request.caseId())`, checked **first**, before any IPFS work, so a
  bad case number fails fast and cheap. Unknown case number -> `404 CASE_NOT_FOUND`.
- After the ledger write succeeds, `EvidenceService` writes the link row. The ledger record's own `caseId` string (already
  written) is the source of truth regardless of what happens to this off-chain link afterwards.
- `CaseResponse.evidenceIds`: the linked evidence ids, cheap (off-chain only, no ledger call), on every case read.
- `GET /api/cases/{id}/evidence` (any authenticated, like other reads): the full ledger-backed `EvidenceResponse` for each
  linked item (one ledger call per item, via `EvidenceService.get`, the same pattern `findByCid` already uses).

## Why enforcement is unconditional, not a flag
An earlier draft proposed an off-by-default `blockevidence.cases.require-existing` flag. The owner's decision text ("case
validation stays in PostgreSQL") and the feature's own wording ("Evidence belongs to a real case entity") point at
enforcement, not an optional check, so the flag was dropped and validation is always on. This is a real, visible change to
Phase 2's `register` endpoint: **before E3, any string was a valid caseId; after E3, the case must exist first.** All three
live scripts (`live_phase2.sh`, `live_fabric_extra.sh`, `live_phase3.sh`) were updated to create their cases before
registering evidence under them (idempotent: a 409 from an already-existing case is expected and ignored).

## Known limitation: the link write is not transactional with the ledger write
If `caseLinks.save(...)` fails after `ledger.createEvidence(...)` already succeeded (an unexpected database error; the case
was already confirmed to exist before any writes started, so this should be rare), the evidence record is correct and
complete on the ledger, but the off-chain index will not show it under that case until reconciled. There is no
reconciliation sweep, the same class of gap as orphaned IPFS pins (D-024, D-037). Listed in `docs/KNOWN_GAPS.md`.

## Verification (TEST_CHECKLIST P3-L section E3; also P2/P2-F regression, unchanged apart from the new prerequisite)
```
register under an UNKNOWN case number:   404  CASE_NOT_FOUND
register under the just-created case:    evidence EV-...
GET /api/cases/{id}:                     200  evidenceIds: ['EV-...']
GET /api/cases/{id}/evidence:            200  linked: ['EV-...']
```
Automated: `EvidenceServiceTest.registeringLinksTheEvidenceToTheCaseAndAnUnknownCaseNumberIsRefusedBeforeAnyStorageWork`
(link saved exactly once per successful register; nothing pinned to IPFS for the rejected attempt), `CaseServiceTest.aCaseListsTheEvidenceLinkedToItByE3AndNothingElse`.

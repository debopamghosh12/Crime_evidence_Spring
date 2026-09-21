# B4: Versioned metadata update

**FEATURE_LIST:** B4 (P0, changed from prototype). **Phase:** 2. **Status:** done, verified live against the
in-memory reference ledger.

## Scoping
"An update never overwrites. Each change creates a new version with a mandatory reason, and older versions
stay readable." Three questions had to be settled: what is updatable, how concurrent edits are handled, and how
old versions are kept.

## What was built
- `PUT /api/evidence/{id}` with `{expectedVersion, reason, description?, location?, notes?}`; at least one
  field required. COLLECTOR, FORENSIC_ANALYST.
- Each update writes a **new** metadata document to IPFS (`metadataVersion + 1`, `previousMetadataCid` = the
  old one) and points the ledger record at it. The old document is never touched and stays pinned.
- `GET /api/evidence/{id}/versions/{n}` returns the record as of version n *and the metadata document that
  version pointed at*.
- **Only metadata is updatable.** `fileCid`, `fileSha256`, `fileSize` are immutable (D-021), as are type and
  case. A different file is a different evidence item.

## Design points
- **`expectedVersion` (optimistic lock).** A stale value is rejected 409 `VERSION_CONFLICT` *before* anything
  is written to IPFS, so a losing edit leaves no orphan document. The ledger checks it again.
- **The base document is hash-checked first.** Building version n+1 on top of tampered metadata would launder
  the tampering into a fresh, ledger-blessed version, so the current document must match the ledger hash or the
  update fails 409 `INTEGRITY_CHECK_FAILED`.
- The reason is mandatory (validated, and enforced again by the ledger).

## Verification (TEST_CHECKLIST.md P2.2)
```
PUT v1->v2 (forensic analyst)     200  version 2  lastAction METADATA_UPDATED  lastReason "Location corrected after audit"
                                       metadata.location Locker 9   metadata.description Wallet (carried over)   metadataVersion 2
GET /versions/1                   200  metadata.location Desk 2        GET /versions/2   200  Locker 9
PUT with expectedVersion 1 (now 2) 409 VERSION_CONFLICT "Expected version 1 but the record is at version 2"
PUT blank reason 400 VALIDATION_FAILED     PUT as JUDGE 403 ACCESS_DENIED
```
Automated (`EvidenceServiceTest`): new version + old version readable + old IPFS document still present,
stale version writes nothing to IPFS, tampered base refused, role without write permission refused and its
pin cleaned up. (`InMemoryLedgerServiceTest`: file fields untouched by an update.)

## Known limits
- Only three fields are editable (description, location, notes). Physical-evidence fields (B7) are not modelled.
- ~~Not yet on Fabric.~~ (Superseded 2026-09-22, see the update below.)

## Update 2026-09-22: on real Fabric
Versions are the peer's own history (`GetHistoryForKey`), so old versions cost no extra storage. Concurrency was tested on Fabric: 4 simultaneous updates with the same `expectedVersion`, 3 rounds, gave exactly one `200` and three `409 VERSION_CONFLICT` every time, with a two-entry history (P2-F.7 F2). The chaincode also refused a stale version by itself when driven directly (P2-F.4).

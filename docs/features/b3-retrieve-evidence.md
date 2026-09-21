# B3: Retrieve evidence (by id or CID, with ledger info and verification status)

**FEATURE_LIST:** B3 (P0, exists in prototype). **Phase:** 2. **Status:** done, verified live against the
in-memory reference ledger.

## Scoping
"Get by evidence ID or by CID, returning metadata, ledger info and current verification status." The task
brief called this "a stub-backed endpoint from Phase 1"; it did not exist (ARCHITECTURE 4.6), so it was built
fresh on the final `LedgerService`.

## What was built
- `GET /api/evidence/{id}` -> `EvidenceResponse`: the **ledger's view** at the top level (status, version,
  custodian, CIDs, hashes, timestamps, disposal state) plus the IPFS metadata document and a `verification` block.
- `GET /api/evidence/by-cid/{cid}` -> a list, matched against the file CID or any metadata CID ever attached.
- `GET /api/evidence/{id}/versions/{n}` and `/history` belong to B4/C3 and share the response type.
- Any authenticated role may read; case-level filtering (A5) is not scheduled and does not exist.

## Decisions (D-022, D-023) the owner should look at
1. **`verification` is `NOT_CHECKED` unless `?verify=true`.** "Current verification status" would otherwise
   mean downloading and re-hashing the full file on every read. This is a deliberate reading of the spec.
2. **CID lookup is a separate route returning a list**, 404 if empty. One CID can belong to several evidence
   items (same file registered twice), so a single-object answer would be ambiguous.
3. **Storage failure degrades, it does not hide the ledger.** If IPFS cannot supply the metadata,
   `metadataAvailable` is false and `metadata` is null but every ledger field is returned (F1). Live: with
   the node stopped, `GET` answered `HTTP 200 status COLLECTED version 2 metadataAvailable False metadata None`.
4. The id path is constrained to `EV-<36 hex/dash>`; anything else is a 404 before any service call.

## Verification (TEST_CHECKLIST.md P2.2)
```
GET by id (AUDITOR)     200  status COLLECTED  version 1  metadata.description "Suspect phone image"  verification NOT_CHECKED
GET by file CID         200  ids: ['EV-...']            GET by metadata CID 200
GET unknown CID         404 NOT_FOUND                   GET unknown id      404 NOT_FOUND
```
Automated: `EvidenceServiceTest` (`ledgerInformationSurvivesAnIpfsOutage`, `getWithVerifyTrueRunsTheCheck`,
`lookupByCidFindsTheEvidenceViaFileOrMetadataCid`), `EvidenceControllerTest` (`everyRoleCanRead`,
`anIdThatIsNotAnEvidenceIdIsA404BeforeAnyServiceCall`).

## Known limits
- Any authenticated user can read any evidence (A5 unscheduled). ADMIN and AUDITOR can read.
- Each GET makes one extra IPFS read for the metadata document (no caching).

## Update 2026-09-22: on real Fabric
Retrieval is now served by `GetEvidence` (one peer, not ordered) and CID lookup by the chaincode's composite-key index (`FindByCid`); LevelDB has no rich queries, so that index is the only way. Lookup by file CID and by metadata CID both returned the record on Fabric, and an unknown CID/id answered 404. With IPFS down the ledger fields are still returned (unchanged).

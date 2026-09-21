# B1: Register evidence

**FEATURE_LIST:** B1 (P0, changed from prototype). **Phase:** 2. **Status:** Spring side done and verified
live against the **in-memory reference ledger**; not yet on Fabric (G2 awaits approval).

## Scoping
"Create an evidence record with type, description, where it was found, collector and case. The server
generates the ID. Metadata goes to IPFS, the CID goes to the ledger." The task brief said to "replace the Phase
1 stub call"; there was none (Phase 1 had no evidence endpoint), so this is built fresh.

## What was built
- `POST /api/evidence`, `multipart/form-data`: a JSON part `metadata` (`RegisterEvidenceRequest`: `caseId`,
  `type` PHYSICAL|DIGITAL, `description`, optional `location`, `collectedAt`, `notes`) and, for DIGITAL, a
  binary part `file`. Roles: COLLECTOR, FORENSIC_ANALYST (D-019). Returns 201 with the ledger record plus the
  metadata document.
- `EvidenceService.register`: id `EV-<uuid>` generated on the server; file hashed and pinned (B2/C1); the
  metadata JSON (`EvidenceMetadata`) hashed and pinned; **the ledger write is last**; then the record is read
  back from the ledger for the response.
- Compensation (F5, partial): if the ledger write fails, the pins made for it are removed, but only those
  the ledger confirms are unreferenced (D-024).

## The collector must come from the token (C-05)
`RegisterEvidenceRequest` has no collector/officer field, so any `collector`, `collectorId` or `createdBy` a
client sends is ignored during deserialisation (D-029). Proven three ways: a unit test asserts the DTO has no
such component and that the service receives the token's user; a controller test posts a forged field and
checks the principal passed on; and live, a request containing `"collector":"forged@evil.example"` and a
zero UUID produced `createdBy` and `metadata.collectorId` equal to the id from `/api/auth/me`.

## What went wrong along the way
- **`NoClassDefFoundError: org/reactivestreams/Publisher`** in the first test run: Spring's
  `MultipartBodyBuilder` needs reactive-streams, which a servlet-only app lacks. Rebuilt with plain
  `LinkedMultiValueMap` (D-025). The unit test caught it; it would have failed on the first live upload.
- Kubo is not private by default (D-020): a default node fetched data from the public network in my probe.
  Development uses `--offline`.

## Verification (details: TEST_CHECKLIST.md P2.2 and Appendix P2-A)
```
POST /api/evidence (DIGITAL, forged collector)   HTTP 201  status COLLECTED  version 1
   createdBy == collector id from JWT? YES
POST /api/evidence (PHYSICAL)                    HTTP 201  fileCid None  fileSha256 None
register as prosecutor / judge / auditor / admin 403 / 403 / 403 / 403      (collector, forensic-analyst: 201)
```
Automated: `EvidenceServiceTest` (register digital/physical, registrant from token, type/file agreement,
compensation cases), `EvidenceControllerTest` (role matrix, validation, forged collector).

## Known limits
- Not run on Fabric. With the default profile this endpoint answers `501`.
- Metadata is stored on IPFS in the clear; do not put personal data in `description`/`notes` (F2 not built).
- No Case entity yet (E1-E3, Phase 3): `caseId` is a validated string.
- Physical-evidence fields (tag/barcode/locker, B7) are not modelled.

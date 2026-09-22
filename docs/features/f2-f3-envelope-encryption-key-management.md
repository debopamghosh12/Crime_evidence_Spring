# F2/F3: Envelope encryption and key management

**FEATURE_LIST:** F2 (P1), F3 (P1). **Phase:** 5. **Status:** built per owner-approved design, verified live
against real PostgreSQL + real IPFS with the `memory-ledger` reference ledger standing in for real Fabric (a
lost operator credential blocked the real-Fabric leg - see "Known limits" below).

## Design
`docs/F2_F3_ENVELOPE_ENCRYPTION_DESIGN.md`, approved 2026-09-22 (Q1 as proposed: authorised = case members +
registrar; Q2 yes: a custody transfer's receiver is auto-wrapped too; Q3 approved: a new `/file` download
endpoint is in scope; Q4 approved: RSA-2048-OAEP-SHA256). CONSTRAINTS.md C-10 (the master-key trust boundary)
added and approved alongside the design, before any code was written.

## What was built
- `crypto/` package: `AesGcmCodec` (stateless AES-256-GCM), `MasterKey` (validates
  `BLOCKEVIDENCE_ENCRYPTION_MASTER_KEY` at startup), `UserKeyPair`/`UserKeyPairRepository`/`UserKeyService`
  (RSA-2048 per user), `EvidenceContentKey`/`EvidenceContentKeyRepository`/`ContentKeyService` (the per-evidence
  AES-256 content key, wrapped per authorised user). No new dependency - plain JCA throughout.
- `EvidenceService`: `register()` generates and wraps the content key; the ledger's `fileSha256`/`metadataSha256`
  are now hashes of the CIPHERTEXT, so `VerificationService` needed zero changes. `update()` requires the acting
  user to hold a wrapped key (new 403 `KEY_NOT_AUTHORISED`). `get()`/`getVersion()`/`findByCid()` decrypt metadata
  with the CALLER's key, degrading to `metadataAvailable=false` for a caller with none.
- New `GET /api/evidence/{id}/file`: streams the decrypted file to an authorised caller.
- `CaseService.addMember`/`removeMember`: re-wrap/revoke the content key of every evidence item linked to the
  case. `sync.EventProcessor`: re-wraps for a custody transfer's receiver on `TRANSFER_INITIATED`.
- `DevUserSeeder`: provisions a keypair for every seeded user (new or pre-existing).
- Flyway `V6__envelope_encryption.sql`: `user_keys`, `evidence_content_keys`.
- 18 new unit tests: `AesGcmCodecTest` (6), `UserKeyServiceTest` (3), `ContentKeyServiceTest` (9) - all against
  REAL RSA/AES crypto (`support/FakeContentKeys`), not mocked. Plus updates to `EvidenceServiceTest`,
  `LifecycleServicesTest`, `CaseServiceTest`, `EventProcessorTest`, `EvidenceControllerTest` for the new
  constructor/method signatures - 226 tests total, all passing.

## Live verification (TEST_CHECKLIST P5-F2F3)
Real PostgreSQL (Flyway V6 applied cleanly) and real IPFS (`--offline`); ledger was `memory-ledger` (see Known
limits). A DIGITAL evidence item registered as `collector` (the case's sole member at the time):
```
Uploaded plaintext: 60 bytes.  Stored on IPFS: 88 bytes (exactly +28 = 12-byte IV + 16-byte GCM tag).
Stored bytes == plaintext? False.  sha256(stored) == ledger fileSha256? True.

GET as collector (registrant):     metadataAvailable=True,  description="Phone found at scene"
GET as analyst (not yet a member): metadataAvailable=False
GET /file as analyst:               403 KEY_NOT_AUTHORISED

POST /api/cases/{id}/members  (add analyst, F3 re-wrap)
GET as analyst NOW:                 metadataAvailable=True,  description="Phone found at scene"
GET /file as analyst NOW:           200, decrypted bytes == the exact original upload bytes

DELETE .../members/{analyst}  (F3 revoke)
GET as analyst AFTER REVOKE:        metadataAvailable=False again
GET /file as analyst AFTER REVOKE:  403 KEY_NOT_AUTHORISED

Tamper test: one byte flipped in the real on-disk IPFS block backing the file's CID (located by exact byte
match via `docker cp` + `cmp`, since flatfs keys are a base32 of the raw multihash, not the CID string - Phase
2's old "grep for a plaintext marker" technique cannot work once content is encrypted).
GET /verify: status=TAMPERED, file.result=TAMPERED, file.expectedSha256 == the ledger's fileSha256 (the
  ciphertext hash), metadata.result=VERIFIED (untouched) - and this call never touched contentKeys at all.
```
Every step matched the design's stated behaviour exactly.

## Known limits
- **Live verification ran against `memory-ledger`, not real Fabric.** The Fabric CA registrar credential needed
  to rebuild the per-user wallet (A2) was lost between sessions (the wallet directory lived outside the repo).
  Recovering it needs a CA identity-secret reissue, which this session's permission policy refused as a
  secret-store write - not something to route around. **Not yet proven against real Fabric:** an actual
  chaincode write under this design, and the custody-transfer-receiver auto-wrap (it lives in
  `sync.EventProcessor`, which only runs against `FabricLedgerService`'s real event stream -
  `InMemoryLedgerService` has no event source, so G3 does not run under `memory-ledger`). DECISIONS D-059 has
  the full account. Follow-up once the registrar is restored: `scripts/fabric/enroll_users.sh`, then repeat this
  check against the real network.
- **Revocation is not retroactive** (design section 8): removing a case member deletes their wrapped key row,
  it does not rotate the content key or re-encrypt content already on IPFS.
- **The backend can decrypt everything** (CONSTRAINTS C-10): the master key is a single point of compromise for
  every user's private key, and therefore every evidence item's content key, by design - the same trust tier
  already accepted for Fabric identities (C-08).
- **`update()` now needs a wrapped key**, narrowing who can update encrypted evidence - a side effect of the
  crypto requirement, not a deliberate new authorisation feature (A5 is still not built for anything else).
- No master-key rotation, no HSM (explicitly out of scope, C-10).

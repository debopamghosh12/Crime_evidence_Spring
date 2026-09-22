# F2/F3: Envelope encryption and key management

**FEATURE_LIST:** F2 (P1), F3 (P1). **Phase:** 5. **Status:** built per owner-approved design, **all 9 checks
verified live against real PostgreSQL, real IPFS and real Fabric** (D-059 first ran against `memory-ledger`
after a lost operator credential blocked the real-Fabric leg; D-060/D-061 recovered it, D-062 confirms both
previously-unconfirmed checks - an actual chaincode write and the custody-transfer-receiver auto-wrap off G3's
real event stream - now pass for real).

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
Real PostgreSQL (Flyway V6 applied cleanly) and real IPFS (`--offline`). Checks 1-8 below first ran against
`memory-ledger` (D-059); checks 9-10 (a real chaincode write, and the transfer-receiver auto-wrap) needed real
Fabric and were confirmed afterward (D-060/D-061/D-062) once the Fabric CA registrar and the dev users' wallet
were rebuilt. A DIGITAL evidence item registered as `collector` (the case's sole member at the time):
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

### Real Fabric (TEST_CHECKLIST P5-F2F3, D-060/D-061/D-062)
The Fabric CA registrar credential and the dev users' wallet (both needed since A2) were lost between sessions;
recovering them needed a CA identity-secret reissue, owner-approved and done via the CA bootstrap admin (same
pattern `scripts/fabric/bootstrap_registrar.sh` already documents for a lost secret - no new access created,
D-060/D-061). With the wallet rebuilt, the app was restarted with `SPRING_PROFILES_ACTIVE=dev` (real
`FabricLedgerService`, real G3 listener against the real chaincode event stream) and the two checks the
`memory-ledger` run could not cover both passed:
```
9. Real chaincode write: POST /api/evidence -> a real evidence record; GET .../history shows a real 64-hex-char
   Fabric transaction id (90549b39...981128), not a stub.
10. Custody-transfer-receiver auto-wrap off the real event stream: analyst starts metadataAvailable=False (not a
    member, not yet the transfer receiver); collector initiates a transfer to them; within the FIRST one-second
    poll afterward analyst's metadataAvailable flips to True and their /file download matches the original
    upload byte-for-byte - sync.EventProcessor.wrapForTransferReceiver genuinely fired off a real
    TRANSFER_INITIATED chaincode event, not just in a unit test.
```
All 9 (10, counting the transfer check separately) items now hold against real Fabric, real PostgreSQL and real
IPFS - no part of F2/F3 remains verified only against a stand-in.

## Known limits
- **Revocation is not retroactive** (design section 8): removing a case member deletes their wrapped key row,
  it does not rotate the content key or re-encrypt content already on IPFS.
- **The backend can decrypt everything** (CONSTRAINTS C-10): the master key is a single point of compromise for
  every user's private key, and therefore every evidence item's content key, by design - the same trust tier
  already accepted for Fabric identities (C-08).
- **`update()` now needs a wrapped key**, narrowing who can update encrypted evidence - a side effect of the
  crypto requirement, not a deliberate new authorisation feature (A5 is still not built for anything else).
- No master-key rotation, no HSM (explicitly out of scope, C-10).

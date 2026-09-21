# C1: SHA-256 file hash

**FEATURE_LIST:** C1 (P0, new). **Phase:** 2. **Status:** done, verified live.

## Scoping
"Compute the hash of the file at upload and store it on the ledger next to the CID." Constraints: the file
can be large (50 MB limit), so it must not be buffered; the hash must be one a verifier can recompute later
from the stored bytes (C2).

## What was built
- `service/HashingInputStream`: wraps the upload stream and updates a SHA-256 digest and a byte counter as the
  bytes flow to IPFS, so the file is read **once**, with no temp copy and no second pass.
- `service/Sha256`: 64 lowercase hex, the exact form stored on the ledger and compared in C2; a streaming
  variant used by verification.
- The hash and byte count are passed to `LedgerService.createEvidence` as `fileSha256` and `fileSize`.
  After the upload, the service checks the counted bytes equal the file's size: a mismatch would mean the CID
  covers truncated content, and the registration is aborted and its pins compensated.

## Decisions and findings
- **The hash is computed by us, not taken from IPFS.** The CID is a hash too, but of the DAG structure; Kubo's
  reported `Size` includes DAG overhead, not file bytes. Recomputing lets a verifier prove the *bytes* match
  what was registered, independently of IPFS internals.
- `skip()` and `mark` are overridden/disabled so bytes cannot reach the consumer without passing through the digest.
- Metadata documents get the same treatment (hash of the exact bytes stored), which is what lets C2 and the
  B4 base-check work.

## Verification
Known-answer test vectors (FIPS 180-4): `SHA-256("abc") = ba7816bf...15ad`, `SHA-256("") = e3b0c442...b855`
in `HashingInputStreamTest` (5 tests, also single-byte vs bulk reads agree over 100 000 random bytes, skipped
bytes still counted, reading the hash mid-stream does not disturb it). Live (TEST_CHECKLIST.md P2.2):
```
local sha256 of the file : 3511f39d2a7814023579467d7097a51ebf2b002c5cc1a4bc0aeafee4b14b0d86     (run 2; run 3 differs, random content)
ledger fileSha256        : 3511f39d2a7814023579467d7097a51ebf2b002c5cc1a4bc0aeafee4b14b0d86
20 MB random file (run 2): local sha256 == ledger sha256 (a5f89d02...), size=20000000, verify -> VERIFIED
```

## Known limits
- SHA-256 only; no algorithm agility field on the ledger record.
- Not yet on Fabric (in this early run; see the update below).

## Update 2026-09-22: on real Fabric
The hash is now written to the Fabric world state (`fileSha256`, immutable: no chaincode function assigns it after creation, and a test proves updates copy it through unchanged). Local hash == ledger hash was re-verified on Fabric for the small file and the 20 MB file (P2-F.6).

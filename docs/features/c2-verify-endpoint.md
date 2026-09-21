# C2: Verify endpoint

**FEATURE_LIST:** C2 (P0, new). **Phase:** 2. **Status:** done. Tamper detection **proven live** by deliberately
corrupting a file on the IPFS node's disk. Ledger side still the in-memory reference ledger.

## Scoping
"`GET /api/evidence/{id}/verify` re-fetches the file, re-hashes it and compares with the ledger. Returns
VERIFIED, TAMPERED or NOT_FOUND." Questions: what exactly is verified, what does NOT_FOUND mean, and what if
the node itself is down?

## What was built
- `VerificationService.verify(record)`: for the **file** (DIGITAL only) and the **metadata document**, read the
  bytes from IPFS, SHA-256 them, compare with the ledger hash. Result per artifact plus an overall verdict.
- `GET /api/evidence/{id}/verify` and `GET /api/evidence/{id}?verify=true`.
- Response: `status`, `ledgerVersion`, `checkedAt` (server time, not evidence time), and per artifact
  `{cid, expectedSha256, actualSha256, result}`.

## Semantics (D-022)
- **TAMPERED > NOT_FOUND > VERIFIED.** A proven mismatch outranks missing content.
- **NOT_FOUND = the ledger has the record but the node no longer has the content.** An unknown evidence id is
  404 like everywhere else.
- **An unreachable node is 503 `STORAGE_UNAVAILABLE`, never NOT_FOUND.** Not being able to look is not
  evidence of absence, and reporting it as missing evidence would be a false and damaging statement.

## Why hashing again matters (a finding, proven live)
IPFS is content-addressed, so one would expect it to detect corruption. It does not, by default: after I edited
one word in the file's block on the node's disk and restarted the node, **the node happily served the altered
bytes under the original CID**. Only the ledger-anchored SHA-256 exposed it. That is the whole value of C1+C2.

## Verification (TEST_CHECKLIST.md P2.2, full output in Appendix P2-A)
Corruption run (real Kubo, real block file, final run):
```
block file on the node: /data/ipfs/blocks/SV/CIQHOCAF7WSOVJJZCGJ33GDDUIAMQFOQLMUDQJMSQSPD7MBJG5RUSVA.data
before: LIVE-EVIDENCE-1790018209-original-content
after : LIVE-EVIDENCE-1790018209-0RIGINAL-content
IPFS still answers a cat for the same CID (content-addressing did NOT catch it):
   cat -> LIVE-EVIDENCE-1790018209-0RIGINAL-content
200 HTTP verify
   status                     TAMPERED
   file.result                TAMPERED
   file.expectedSha256        770805fda4eaa5391193bd9863a200c815d05b28382592849e3fb02937634954
   file.actualSha256          010378697d295c54eab93e49f6ab0ca00824da6b10af3bd6742ebc847d086825
   metadata.result            VERIFIED
```
Deleted block: `removed-block` -> `status NOT_FOUND`, `file.actualSha256 None`, `metadata.result VERIFIED`, 161 ms.
Node stopped: `503 STORAGE_UNAVAILABLE`. Untouched: `status VERIFIED`, expected == actual.
Automated (`EvidenceServiceTest`): corrupted file, corrupted metadata, lost file, TAMPERED-beats-NOT_FOUND,
unreachable node throws, unknown id 404 (all using `FakeIpfsClient.corrupt()/lose()/down`).

## Known limits
- Verifies the two artifacts from the ledger's *current* record; verifying an old version is not offered.
- Full-file download per call, so it is deliberately opt-in on GET. There is no scheduled sweep (C5, P2 stretch).
- The expected hash comes from the reference ledger in this run; the Fabric-anchored version is the point of G2.

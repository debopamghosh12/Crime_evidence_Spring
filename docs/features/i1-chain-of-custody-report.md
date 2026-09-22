# I1: Chain-of-custody PDF report

**FEATURE_LIST:** I1 (P1, "OpenPDF / iText"). **Phase:** 5. **Status:** done, verified live against real Fabric
with a genuine multi-step timeline.

## Design point, confirmed before any code was written
The owner asked this to be settled up front: the report must be producible using ONLY ledger/timeline/hash data
- never requiring file decryption - so a Judge or Auditor who was never wrapped in for a given item's content
(F2/F3) can still generate the exact same report as one who was. Confirmed and built exactly that way:

- **`ReportService` depends on `LedgerService` and `VerificationService` only** - no `ContentKeyService`,
  `IpfsClient` or `EvidenceMetadata` reference anywhere in the class. This is an architectural guarantee, not a
  coding discipline: the class has no way to reach decrypted content even if it tried.
- **Evidence details** = the ledger-native record (case, type, status, custodian, created/updated by and at,
  last action/reason) - not the off-chain metadata document's free-text description/location/notes, which is
  the CONTENT F2/F3 protects. This mirrors I3's own design line ("see integrity status only, without seeing the
  content").
- **The verification result is a fresh `VerificationService.verify()` call**, which already needs no content
  key - only IPFS reachability, to re-fetch ciphertext and hash it (proven in F2/F3's own live verification). A
  report-requester with no wrapped key gets an identical, fully valid verification result.

## What was built
- `service/ReportService.generateChainOfCustodyReport(evidenceId, requestedBy)`: loads the current record and
  full history from `LedgerService`, runs a fresh `VerificationService.verify()`, and renders one PDF (OpenPDF)
  with: evidence details, content hashes as recorded on the ledger, the full timeline, a dedicated
  actors/transaction-ids table, and the verification result.
- `GET /api/evidence/{id}/report` - same `Permissions.READ_EVIDENCE` as every other read (any role that can view
  evidence can generate this), audited as a DOWNLOAD (A6, since it re-verifies content hashes).
- New dependency: **OpenPDF 2.2.2**, not iText (LGPL/MPL vs. AGPL/commercial - DECISIONS D-064).

## Layout finding (fixed before shipping, D-064)
The first attempt crammed all seven fields (version, tx id, timestamp, action, actor, role, reason) into one
table on A4 portrait - the 64-hex-character transaction id and the 36-character actor UUID both wrapped
mid-string, which is unreadable in the real PDF, not just a test artifact. Fixed by splitting into a narrative
"custody timeline" table (version/timestamp/action/role/reason) and a dedicated "actors and transaction ids"
table with enough width for both long tokens to render on one line - found by reading the actual generated PDF
back with `PdfTextExtractor` in a unit test, not by inspection alone.

## Verification
**3 unit tests** (`ReportServiceTest`, a REAL `InMemoryLedgerService` and `FakeIpfsClient`, not mocked): a
genuine 5-step timeline (register, status change, transfer initiate, transfer accept, disposal request), then
the generated PDF is read back with OpenPDF's own `PdfTextExtractor` and asserted to contain - verbatim - every
hash, every one of the 5 real transaction ids, every action, every actor id, and the fresh verification result;
and asserted to NOT contain the metadata's free-text description. A second test confirms a tampered component
shows `TAMPERED` in the report; a third confirms 404 for unknown evidence.

**Live run against real Fabric** (`verify_i1.sh`): the same 5-step timeline built through the real API
(register -> status change -> transfer initiate -> transfer accept -> disposal request), then the report
generated **as the JUDGE role**, first confirming via `GET /api/evidence/{id}` that the judge held
`metadataAvailable=false` (never wrapped in for this item's content). The report generated successfully (200,
valid PDF). Extracting its text and comparing against the real `GET .../history` response line by line:
```
Ledger history txIds:                          PDF "Actors and transaction ids" table:
v1 CREATED           f8bca3a9...5a11e9          v1  f47ff616...  f8bca3a9...5a11e9   MATCH
v2 STATUS_CHANGED    b8c8dbf3...8b95d2d         v2  b9481c66...  b8c8dbf3...8b95d2d   MATCH
v3 TRANSFER_INITIATED a3a73a94...0dc8d8ca       v3  f47ff616...  a3a73a94...0dc8d8ca   MATCH
v4 TRANSFER_ACCEPTED  47aca88a...8293d4ba4      v4  b9481c66...  47aca88a...8293d4ba4  MATCH
v5 DISPOSAL_REQUESTED abcc89f4...ffcc075e7      v5  286529a7...  abcc89f4...ffcc075e7  MATCH
```
Metadata CID and SHA-256 in the PDF also matched the register response exactly, and the verification section
showed `VERIFIED` with `expected == actual` for the metadata hash. Every hash and transaction id in the report
is a real, independently-checkable value from the real ledger - not a stub.

## Known limits
- No pagination handling was stress-tested for a VERY long timeline (dozens of versions) - OpenPDF's `Document`
  paginates automatically, but this was not exercised with more than 5 versions.
- The PDF has no digital signature or tamper-evidence of its own (a court could still ask "how do I know this
  PDF wasn't edited after generation?") - out of scope; the report's own claims are independently checkable
  against the ledger by design (every hash/tx-id is a real value a reader can look up), which is a different
  property than the file itself being signed.

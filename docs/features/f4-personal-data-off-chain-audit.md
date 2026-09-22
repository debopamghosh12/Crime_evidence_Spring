# F4: Personal data off-chain — audit

**FEATURE_LIST:** F4 (P1, "Design rule + code review"). **Phase:** 5. **Status:** audited 2026-09-22. Verification
pass only, per the owner's instruction ("this should already be true... treat this as a verification pass, not
new code, unless it finds a violation") — **no code changed**, one residual documented (not a violation).

## What was checked
Every path that writes to the ledger, end to end:
- Chaincode `chaincode/evidence/model.go` (`EvidenceRecord`, `Disposal`, `Transfer` - the only structs ever
  written to world state) and every write function's argument list in `evidence.go`/`custody.go`.
- Java `ledger/LedgerActor`, `ledger/LedgerNewEvidence` (everything `LedgerService` accepts) and every
  `FabricLedgerService.submit(...)` call site.
- A full-text search across `service/`/`ledger/` for `.email()`, `.fullName`, `.department` (the three personal
  fields `User` carries) - to make certain none of them is ever reachable from a ledger call.
- `domain/EvidenceMetadata` (the document actually stored on IPFS, off-chain by design) for comparison.

## Result: C-06 holds as built
Every field the system automatically writes to the ledger is one of: an opaque user UUID (`createdBy`,
`updatedBy`, `currentCustodian`, `Disposal.requestedBy`, `Transfer.from`/`to`), a role NAME (`createdByRole`,
`updatedByRole`, `Transfer.toRole`), a hash, a CID, a case-number string, a status/type enum, a ledger timestamp,
or a transaction id. **No email, full name or department is ever passed to `LedgerService` or the chaincode
anywhere in the codebase** - the grep across `service/`/`ledger/` for the three personal `User` fields found zero
matches outside `auth`/`audit` code (which is correctly off-chain, PostgreSQL-only) and one legitimate
`AuthController`/`DevUserSeeder` usage. `EvidenceMetadata` (IPFS, not the ledger) also keeps `collectorId` as a
UUID, never an email or name - stronger than C-06 strictly requires, since C-06 only governs the ledger.

## One residual, found and documented, not a violation
Several **free-text** fields DO reach the immutable ledger by design, because the feature list itself requires
them: `reason` (`StatusChangeRequest`, `DisposalRequestBody`, `TransferRequest`) and `note`/`notes`
(`DisposalDecisionBody`, `TransferDecisionBody`, `TransferRequest`). Every one is validated for LENGTH only
(`@Size(max = 1000)`), never content - nothing stops a user typing a name, an address, or other personal detail
into one of these fields, and once written it is permanent (the same reason F4 exists at all: "personal data is
kept off-chain because it cannot be erased once written").

This is not a C-06 violation: C-06 is a rule about what the SYSTEM automatically stores, and these fields exist
for a legitimate, required purpose (B5 "needs a reason", D2 "a reason is mandatory") - not a channel the system
adds personal data through. It is a genuine, human-input residual risk worth being honest about in the report.
Content-scanning or redacting free-text fields for PII is well outside this project's scope and was not asked
for; **accepted as a known gap**, not fixed.

## Conclusion for F2/F3
This audit is also the answer to "are F2/F3 solving a real problem": **yes, but a different one than personal
data on the ledger.** The ledger itself is clean by design. The real exposure F2 (envelope encryption) addresses
is the one C-09 already names: **file and metadata CONTENT on IPFS** (the file bytes and the descriptive metadata
document, which DOES legitimately carry description/location/notes) is public to anyone who learns the CID, and
CIDs are returned by the API and stored on the ledger. F2/F3 protect that content, not the ledger.

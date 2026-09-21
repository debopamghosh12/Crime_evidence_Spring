# G2: Chaincode

**FEATURE_LIST:** G2 (P0, changed from prototype). **Phase:** 2. **Status: DESIGN ONLY. NOT IMPLEMENTED.**
Per the task, the design is shown for approval before any chaincode is written. Nothing on Fabric has been
built, deployed or run.

## What exists
- **`docs/CHAINCODE_DESIGN.md`**: the full design, for approval. Summary: a new Go chaincode `evidence`; one
  JSON record per item plus a CID composite-key index (LevelDB has no rich queries); functions
  `CreateEvidence, UpdateEvidence, UpdateStatus, RequestDisposal, ApproveDisposal, RejectDisposal` (writes) and
  `GetEvidence, GetHistory, FindByCid` (reads); no delete function; file fields immutable; ledger timestamps
  only; role checks against an ACL table; chaincode events for the future G3 listener.
- **`InMemoryLedgerService`** (`ledger/`, profile `memory-ledger`): an executable specification of every rule in
  the design, with 14 tests (`InMemoryLedgerServiceTest`) covering the role table, `expectedVersion`, immutable
  file fields, status transitions, disposal, history and the CID index. The Go tests will be written against the
  same scenarios.
- **The Spring boundary** the chaincode must satisfy: the final `LedgerService` (DECISIONS D-016) and the closed
  error-code set (D-017), whose first six codes are exactly what the chaincode is designed to return.

## Facts that shaped the design (checked, not assumed)
Fabric **2.5.15**, so the modern `fabric-gateway` client works; **LevelDB** state DB, so no rich queries; two
orgs, so both must endorse; the old chaincode `basic` has no source on disk, so nothing to port; the copies of
`fabric-samples` are trimmed (no `network.sh`) and the network has been stopped for 4 months.

## Decisions awaiting the owner (CHAINCODE_DESIGN.md section 9)
G1 new chaincode `evidence` in Go; G2 immutable file fields; G3 the role table; G4 disposal from any
non-DISPOSED status; G5 roles passed as arguments now, certificate attributes in Phase 3, accepting that until
then a compromised backend could claim any role; G6 approve dependencies `fabric-gateway` + `grpc-netty-shaded`
(C-04); G7 revive the stopped network first, else recreate.

## Not done (and why)
Everything else: no Go code, no packaging, no deployment, no `FabricLedgerService`, no live Fabric run. The
default profile therefore answers evidence calls with `501 NOT_IMPLEMENTED`.

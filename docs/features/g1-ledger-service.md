# G1: LedgerService abstraction with an unimplemented Fabric stub

**FEATURE_LIST:** G1 (P0, new). **Phase:** 1. **Status:** interface + stub done, verified 2026-09-22.
The real Fabric implementation is Phase 2/3.

## Scoping
"One interface for all ledger calls. Fabric implementation now; a web3j implementation could be added
later." The PDF's Phase 1 says "LedgerService with Fabric"; the project owner's instruction was an
*unimplemented stub*, which is what was built. Constraint C-01: only the `ledger/` package may know
Fabric exists.

## What was built
- `ledger/LedgerService` (interface): `createEvidence, updateEvidence, updateStatus, initiateTransfer,
  acceptTransfer, getEvidence, getHistory, health()`, mirroring the chaincode list in G2. Every write
  takes `actorId` (from the JWT, C-05) and only ids/hashes/CIDs (C-06).
- Records: `LedgerEvidenceRecord`, `LedgerTxResult`, `LedgerHistoryEntry`, `LedgerHealth`.
- `ledger/FabricLedgerService` (`@Service`): all operations throw `LedgerNotImplementedException` (which
  extends `exception/FeatureNotImplementedException` so the exception handler maps it to 501 without
  importing `ledger/`); `health()` returns UNKNOWN with the configured channel and chaincode.
- **No `fabric-gateway` dependency yet** (D-004). The stub imports nothing from Fabric.

## Decisions
- **Signatures are provisional.** They will be revised in Phase 2 when the real chaincode calls force the
  shapes; documented in the interface's Javadoc. `rejectTransfer` waits for Phase 3 (D2). Status is a
  `String` until the D1 state machine defines the enum.
- **`health()` returns `LedgerHealth`, not a boolean** (D-008): the stub must be able to say UNKNOWN.
- Considered but not built: defining only `health()` now (approved default was the full G2 list, Q4).

## Verification
`HealthIndicatorsTest`: `everyFabricStubOperationThrowsNotImplemented` (all 7 operations) and
`fabricStubReportsUnknownWithItsConfigurationNotDown` passed. Live: `/actuator/health` (ADMIN) reports
```
"ledger":{"details":{"detail":"FabricLedgerService not implemented (Phase 1 stub); configured channel=crimechannel chaincode=basic"},"status":"UNKNOWN"}
```
C-01 checks (imports/dependencies/callers) are in `docs/TEST_CHECKLIST.md` section 7: no Fabric imports, no
Fabric dependency, nothing outside `ledger/` imports ledger types.

## Known limits
Nothing calls the interface yet except the health path. A local Fabric network from the prototype exists
on this machine as stopped Docker containers (`peer0.org1`, `orderer`, CAs, chaincode `basic`), useful for
Phase 2; not touched.

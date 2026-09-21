# G2: Chaincode `evidence` (Go) and the real FabricLedgerService

**FEATURE_LIST:** G2 (P0, changed from prototype) with G1's Fabric implementation. **Phase:** 2.
**Status: implemented and verified on the real Fabric network (2026-09-22).** Design: `docs/CHAINCODE_DESIGN.md` (owner-approved,
G1-G7 as proposed); as-built notes in its section 10; operations in `docs/FABRIC_RUNBOOK.md`.

## Scoping
Implement exactly the approved design: a new Go chaincode `evidence` with `CreateEvidence, UpdateEvidence, UpdateStatus,
RequestDisposal, ApproveDisposal, RejectDisposal, GetEvidence, GetHistory, FindByCid`, role checks, immutable file fields, no delete,
ledger timestamps, CID composite-key index, events; plus the Java `FabricLedgerService` behind the unchanged `LedgerService` interface.
Constraint **C-08** (owner-added) applies: the role is supplied by the backend and is not authenticated until A2.

## What was built
- `chaincode/evidence/`: `evidence.go` (contract), `policy.go` (role table, transitions, formats, error codes), `model.go`
  (wire types), `main.go`; tests `evidence_test.go` (22) with a purpose-built `fake_ledger_test.go`.
- `FabricLedgerService` (Fabric Gateway, gRPC/TLS to the Org1 peer, `submitTransaction` waits for commit), `FabricErrors`,
  extended `FabricProperties` (identity by file path, C-07), config in `application.yml`.
- Deployment and verification scripts in `chaincode/scripts/` and `scripts/live/`.

## How it was verified (all real output is in TEST_CHECKLIST.md section P2-F)
1. **22 Go tests** against a fake ledger that models failed-transaction rollback, composite keys, history and counts `DelState`
   calls (must be 0). They mirror `InMemoryLedgerServiceTest` scenario for scenario.
2. **Deployed for real:** installed on both peers, approved by both orgs, committed: `Name: evidence, Version: 1.1, Sequence: 2`.
3. **The chaincode driven directly through the `peer` CLI, bypassing Spring, with both orgs endorsing:** the chaincode itself
   refused JUDGE and ADMIN creating (`FORBIDDEN_ROLE`), a stale version (`VERSION_CONFLICT`), skipping a status (`INVALID_STATE`),
   setting DISPOSED directly (`INVALID_ARGUMENT`), a COLLECTOR approving disposal, and any write after DISPOSED; `DeleteEvidence`
   answers `Function DeleteEvidence not found in contract EvidenceContract`.
4. **The whole Phase 2 checklist through Spring -> real Fabric**, diffed against the in-memory reference run after normalising ids,
   hashes and timestamps: identical except the health line naming the ledger.
5. **Fabric-only checks:** an API-reported txId exists on the peers' ledger (and a made-up one does not); 4 concurrent updates with the
   same `expectedVersion`, 3 rounds: exactly one `200`, three `409 VERSION_CONFLICT`, history length 2; peer stopped -> `503
   LEDGER_UNAVAILABLE`, health DOWN, self-recovery in ~4 s; data survives an application restart.

## Defects the real run found (and the unit tests could not)
- **v1.0 could not read physical items**: the contract framework validates return values against a schema where fields are required
  unless tagged `metadata:",optional"` (D-032). Fixed, regression test added, redeployed as v1.1 seq 2.
- **Error message ran into the gateway's next fragment** (D-034). Fixed, regression test with the real fragment shape.
- Both were found on the first real-peer query / first full run, which is the argument for having run against real Fabric.

## Known limits (honest)
- **C-08:** the chaincode trusts the role the backend passes. It authenticates only the MSP (Org1/Org2). A compromised backend could
  claim any role. Closed by A2 in Phase 3.
- Not tested: endorsement failure of one org, orderer outage, more than one peer connection, load, chaincode events (nothing consumes
  them until G3), certificate expiry/rotation.
- Ledger test data is permanent (case ids `FAB-DIRECT`, `FAB-WIRE`, `FAB-LIVE-1..4`); block height 48 -> 93.
- Status transitions are provisional until D1 (Phase 3); no transfer functions yet (D2).

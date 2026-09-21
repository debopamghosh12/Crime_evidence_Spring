# Rollback

## Chaincode + FabricLedgerService (G2), written 2026-09-22 before the edit

**Revert target:** git tag `phase2-spring-done` (commit `9e159b5`: Phase 2 Spring side with the in-memory
reference ledger, 117 tests). Earlier points: `phase1-done` (`c1a4d4b`, pushed), `baseline-docs`.
**Nothing from this stage is committed until the owner reviews it.**

**Repository changes:** new top-level `chaincode/evidence/` (Go, incl. a `vendor/` directory), `FabricLedgerService`
(real), `FabricProperties`, `pom.xml` (adds `fabric-gateway` and `grpc-netty-shaded`), `application.yml`,
new Java tests, docs. Roll back with `git stash -u` (keeps a copy) or `git reset --hard phase2-spring-done && git clean -fd`.

**Update 2026-09-22:** the chaincode stage is complete and UNCOMMITTED. New repo paths: `chaincode/` (Go module + `scripts/`), `scripts/live/`,
`src/test/resources/fabric/`, `docs/FABRIC_RUNBOOK.md`. All Fabric containers were stopped again at the end of the session.

**Fabric-side effects that git does NOT undo** (this stage revives and modifies a network that had been
stopped for 4 months):
- The existing Docker containers `ca_org1`, `ca_org2`, `ca_orderer`, `orderer.example.com`,
  `peer0.org1.example.com`, `peer0.org2.example.com` are STARTED (they existed; nothing was recreated unless
  the section "Network revival log" in TEST_CHECKLIST.md says so). Stop them with
  `docker stop ca_org1 ca_org2 ca_orderer orderer.example.com peer0.org1.example.com peer0.org2.example.com`.
- Chaincode `evidence` is installed on both peers, approved by both orgs and **committed to the channel definition**: v1.0 seq 1
  (superseded) and **v1.1 seq 2** (current). A committed chaincode definition cannot be removed from a channel; a new version needs a new
  sequence. Chaincode containers/images named `dev-peer0.org*-evidence_1.0-*` are created; remove with
  `docker rm -f` / `docker rmi` on those names.
- Ledger data written by the live run (evidence records, blocks) stays in the peers' ledgers. It is test data
  and is tagged by case ids `FAB-DIRECT`, `FAB-WIRE`, `FAB-LIVE-1..4`. The channel went from block 48 to 93. It cannot be deleted from a Fabric ledger; to discard it, remove the
  peer containers/volumes and recreate the network.
- Verification containers `be-postgres` (5433) and `be-ipfs` (5001, `--offline`) as before.
- `E:\FinalYrProjects\EvidenceBackendabric-samples` is only READ (crypto material paths); nothing there is modified.

**Re-check after rollback:** `git describe --tags` shows `phase2-spring-done`; `./mvnw test` reports 117 passing;
`git status` clean; the default profile answers evidence calls 501 (stub) again.

---
## Phase 2 build (evidence management: B1-B5, C1-C3, G2), written 2026-09-22 before the edit

**Revert target:** git tag `phase1-done` (commit `c1a4d4b`, pushed to `origin`). Phase 2 work is
uncommitted until the owner reviews it, so this tag is the revert point for everything below.

**What Phase 2 adds:** `src/main/java/.../domain/`, evidence classes in `controller/`, `dto/`, `service/`,
`ledger/`, `storage/`, `exception/`; `docs/CHAINCODE_DESIGN.md`; `docs/features/{b1..b5,c1..c3,g2}-*.md`;
later, after design approval, a top-level `chaincode/` directory.
**What it modifies:** `HttpIpfsClient`, `IpfsClient`, `IpfsProperties`, `LedgerService`, `FabricLedgerService`,
`GlobalExceptionHandler`, `application.yml`, `SecurityConfig` only if a route rule is needed, and docs
(`ARCHITECTURE`, `DECISIONS`, `FLOW`, `TEST_CHECKLIST`, `HANDOVER`, `ROLLBACK`).

**How to roll back**
1. Everything: `git stash -u` (keeps a copy) or `git reset --hard phase1-done && git clean -fd`.
   Check `git status` first; nothing from Phase 2 is committed yet.
2. One file: `git checkout phase1-done -- <path>`.

**Non-git side effects:** Docker containers `be-postgres` (5433) and `be-ipfs` (5001) are started for
verification; Phase 2 pins real files into the Kubo node in `be-ipfs`. `docker rm -f be-ipfs be-postgres` and
`docker volume rm be-postgres-data` discard them. No Fabric container is started or modified before design
approval. Phase 2 adds no database migration.

**Re-check after rollback:** `git describe --tags` shows `phase1-done`; `./mvnw test` reports 53 passing.

---

## Phase 1 build (kept for history), written 2026-09-21
Revert target was tag `baseline-docs` (commit `66c8932`). Phase 1 is now committed (`c1a4d4b`, tag
`phase1-done`); the instructions above supersede it.

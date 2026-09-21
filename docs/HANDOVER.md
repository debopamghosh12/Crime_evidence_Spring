# Handover

<!-- Most recent session first. 5 lines per entry: date + did / left / broken / watch out for. -->

> **Date correction (2026-09-22, local IST):** sessions 3 and 4 were first dated 2026-09-23/24 and 2026-09-25 in several docs, dates I inferred without reading a clock. The machine clock and every log/ledger timestamp show all of this work happened on the night of 2026-09-21 (UTC) / 2026-09-22 (IST). All doc dates were corrected to 2026-09-22; the UTC timestamps inside logs and on the ledger were never touched.

## 2026-09-22 — Session 4 (chaincode G2 + real FabricLedgerService built and verified on Fabric; STOP before Phase 3)
- **Did:** C-08/C-09 added; Phase 2 Spring side committed (`9e159b5`, tag `phase2-spring-done`; pushed 2026-09-22 with `phase2-done`); Go chaincode `evidence` (22 tests) deployed as v1.1 seq 2 on the REVIVED network; real `FabricLedgerService`; 130 Java tests; the whole Phase 2 checklist re-run through real Fabric (identical to the reference run) plus Fabric-only checks (real txIds, MVCC race, peer outage, restart persistence); runbook and scripts saved in the repo.
- **Left:** Owner review of the chaincode stage; then Phase 3 (D1-D3 status machine + two-step custody, E1-E3 cases, A2 per-user Fabric identities which closes C-08). This stage is UNCOMMITTED (revert point: tag `phase2-spring-done`); consider committing + tagging `phase2-done` and pushing.
- **Broken:** Nothing known. **KNOWN GAPS, explicitly left as-is by owner decision (full list: docs/KNOWN_GAPS.md, do NOT drop):** (1) single-org endorsement failure UNTESTED; (2) orderer outage UNTESTED; (3) peer failover NOT BUILT (one peer connection, observed 503 + self-recovery); (4) load / concurrent-upload capacity UNTESTED; plus C-08 (chaincode trusts the backend-supplied role, A2 in Phase 3), public-IPFS exposure (C-09), permanent ledger test data (block 48 -> 93).
- **Watch out:** the Fabric toolchain lives in WSL (`/home/debop/crime-evidence-mgmt`), not on `E:`; read docs/FABRIC_RUNBOOK.md first (Git Bash path rewriting, WSL log redirection, `--waitForEvent`, contract-schema `optional` tags); a committed chaincode definition cannot be edited (new VER + higher SEQ); default Kubo is public (C-09, keep `--offline`); all Fabric containers are stopped now.
- **Next session start:** revive with the commands in FABRIC_RUNBOOK section 2, set the FABRIC_* env paths, run `scripts/live/live_phase2.sh` as a smoke test.

## 2026-09-22 — Session 3 (Phase 2 Spring side built; STOPPED before implementing the chaincode)
- **Did:** Phase 2 Spring side for B1-B5, C1-C3: final LedgerService/IpfsClient (DECISIONS D-016), real IPFS client, EvidenceService/VerificationService/controller, in-memory reference ledger (profile `memory-ledger`); 117 tests green; live run vs real Postgres + real offline Kubo incl. deliberate block corruption -> TAMPERED and deleted block -> NOT_FOUND; 9 feature docs; `docs/CHAINCODE_DESIGN.md` (G2) written for approval.
- **Left:** OWNER must answer CHAINCODE_DESIGN.md section 9 (G1-G7: chaincode `evidence` in Go, role table, dependencies `fabric-gateway`+`grpc-netty-shaded`, revive network). Then: implement chaincode + Go tests, `FabricLedgerService`, bring the Fabric network up, re-run TEST_CHECKLIST P2 through Fabric. Phase 2 work is UNCOMMITTED (revert point: tag `phase1-done`).
- **Broken:** Nothing known, but nothing evidence-related runs on Fabric: the default profile answers evidence calls with 501. All Phase 2 live results are against the in-memory ledger, labelled as such.
- **Watch out:** default Kubo joins PUBLIC IPFS (D-020), dev container must use `daemon --offline`; fabric-samples copies here have no `network.sh` and the old network is 4 months stopped; permission matrix D-019 is a proposal; verify is opt-in on GET (D-022); Git Bash mangles `/data/...` docker args (`MSYS_NO_PATHCONV=1`); long shell heredocs sometimes fail to parse, write files with the Write tool.
- **Next session start:** read CHAINCODE_DESIGN.md first; `docker start be-postgres`; recreate `be-ipfs` with `--offline` if it was removed; env secrets per TEST_CHECKLIST section 0.

## 2026-09-22 — Session 2 (Phase 1 built and verified; STOP before Phase 2)
- **Did:** git init + tag `baseline-docs`; CONSTRAINTS seeded (C-01..C-07); project generated (Maven, Boot 4.1.1, Java 21); Phase 1 built: K1, K3, A1, A3, G1 stub, F1 stub, G4; 53 tests green; live run vs real Postgres 16 + Kubo passed (TEST_CHECKLIST.md).
- **Left:** Owner review of Phase 1, then Phase 2 (B1-B5, C1-C3, G2). Phase 1 was NOT committed at the end of that session; **update: it is now committed (`c1a4d4b`), tagged `phase1-done` and pushed to origin.**
- **Broken:** Nothing known. Gaps: no real-DB automated test (Testcontainers, L2); no purge of old refresh tokens; access token outlives deactivation by up to 15 min.
- **Watch out:** Boot 4 (not 3.5): starters/packages renamed, Jackson 3 vs jjwt's Jackson 2; JVM tz pinned to UTC in main(); local Postgres owns port 5432 so dev DB is container on 5433; env secrets required (see TEST_CHECKLIST 0).
- **Next session start:** `docker start be-postgres be-ipfs`; existing stopped Fabric prototype containers (peer0.org1, orderer, CAs, chaincode `basic`) can back Phase 2's FabricLedgerService; `LedgerService`/`IpfsClient` signatures are provisional and expected to change.

## 2026-09-21 — Session 1 (setup, Phase 1 design only)
- **Did:** Created CLAUDE.md, docs/ skeleton, FEATURE_LIST.md (generated from the PDF; counts verified 58 = 24 P0 + 22 P1 + 12 P2), ARCHITECTURE.md draft, DECISIONS D-001/D-002.
- **Left:** Answer Q1–Q8 at the bottom of ARCHITECTURE.md, approve dependencies (§7), then build Phase 1 (K1, K3, A1, A3, G1, F1, G4); no code written yet.
- **Broken:** Nothing built yet. Project is not a git repo (ROLLBACK.md has no revert target); Maven/Gradle not on PATH, so the project needs generating (start.spring.io or IntelliJ) first.
- **Watch out:** A4 and A5 appear in no build phase yet Phase 1 needs users (Q5); CONSTRAINTS.md is still empty, so propose seed entries from the CLAUDE.md hard constraints.


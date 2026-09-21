# Handover

<!-- Most recent session first. 5 lines per entry: date + did / left / broken / watch out for. -->

## 2026-09-23 — Session 3 (Phase 2 Spring side built; STOPPED before implementing the chaincode)
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


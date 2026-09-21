# Handover

<!-- Most recent session first. 5 lines per entry: date + did / left / broken / watch out for. -->

## 2026-09-22 — Session 2 (Phase 1 built and verified; STOP before Phase 2)
- **Did:** git init + tag `baseline-docs`; CONSTRAINTS seeded (C-01..C-07); project generated (Maven, Boot 4.1.1, Java 21); Phase 1 built: K1, K3, A1, A3, G1 stub, F1 stub, G4; 53 tests green; live run vs real Postgres 16 + Kubo passed (TEST_CHECKLIST.md).
- **Left:** Owner review of Phase 1, then Phase 2 (B1-B5, C1-C3, G2). Phase 1 is NOT committed yet (working tree only), so commit + tag it first (`phase1-done`).
- **Broken:** Nothing known. Gaps: no real-DB automated test (Testcontainers, L2); no purge of old refresh tokens; access token outlives deactivation by up to 15 min.
- **Watch out:** Boot 4 (not 3.5): starters/packages renamed, Jackson 3 vs jjwt's Jackson 2; JVM tz pinned to UTC in main(); local Postgres owns port 5432 so dev DB is container on 5433; env secrets required (see TEST_CHECKLIST 0).
- **Next session start:** `docker start be-postgres be-ipfs`; existing stopped Fabric prototype containers (peer0.org1, orderer, CAs, chaincode `basic`) can back Phase 2's FabricLedgerService; `LedgerService`/`IpfsClient` signatures are provisional and expected to change.

## 2026-09-21 — Session 1 (setup, Phase 1 design only)
- **Did:** Created CLAUDE.md, docs/ skeleton, FEATURE_LIST.md (generated from the PDF; counts verified 58 = 24 P0 + 22 P1 + 12 P2), ARCHITECTURE.md draft, DECISIONS D-001/D-002.
- **Left:** Answer Q1–Q8 at the bottom of ARCHITECTURE.md, approve dependencies (§7), then build Phase 1 (K1, K3, A1, A3, G1, F1, G4); no code written yet.
- **Broken:** Nothing built yet. Project is not a git repo (ROLLBACK.md has no revert target); Maven/Gradle not on PATH, so the project needs generating (start.spring.io or IntelliJ) first.
- **Watch out:** A4 and A5 appear in no build phase yet Phase 1 needs users (Q5); CONSTRAINTS.md is still empty, so propose seed entries from the CLAUDE.md hard constraints.


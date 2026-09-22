# Handover

<!-- Most recent session first. 5 lines per entry: date + did / left / broken / watch out for. -->

> **Date correction (2026-09-22, local IST):** sessions 3 and 4 were first dated 2026-09-23/24 and 2026-09-25 in several docs, dates I inferred without reading a clock. The machine clock and every log/ledger timestamp show all of this work happened on the night of 2026-09-21 (UTC) / 2026-09-22 (IST). All doc dates were corrected to 2026-09-22; the UTC timestamps inside logs and on the ledger were never touched.

## 2026-09-22 — Session 5 (Phase 3 built and verified: D1-D3, E1-E2; E3 and A2 held for the owner)
- **Did:** chaincode v1.2 seq 3 (two-step custody: initiate/accept/reject/cancel, pending list; 33 Go tests); status state machine, custody timeline, cases and case officers on the Spring side (171 Java tests); all verified live Spring -> real Fabric + real PostgreSQL (`scripts/live/live_phase3.sh`), the whole Phase 2 checklist re-run through Fabric on v1.2 (identical except ledger timestamps); A2 identity/enrollment DESIGN written after a spike against the real CA (`docs/A2_IDENTITY_DESIGN.md`), NOT implemented. Bugs found live and fixed: case lead change 500 (unique key vs Hibernate flush order), Java null `transfer` on old records.
- **Left:** OWNER must (1) answer A2-Q1..Q8 before any A2 code (it changes how every write is authenticated; B1-B5 untouched so far), (2) choose E3 option A (chaincode index) or B (Spring link table), it touches Phase 2, (3) review Phase 3 and say whether to commit/tag it (`phase3-done`). Phase 3 work is UNCOMMITTED (revert point: tag `phase2-done`).
- **Broken:** Nothing known. **KNOWN GAPS still explicitly untested/unbuilt by owner decision (docs/KNOWN_GAPS.md): single-org endorsement failure, orderer outage, peer failover, load;** plus C-08 (role trusted from the backend until A2), no user-lookup endpoint, status not custodian-only, no case-level read access, E3.
- **Watch out:** chaincode changes need a NEW version and sequence (`VER=1.x SEQ=n bash chaincode/scripts/deploy_cc.sh`; forgetting them installs a stray package); mocked repositories hid a real DB bug, use the live scripts; Fabric toolchain is in WSL (FABRIC_RUNBOOK); Kubo stays `--offline` (C-09).
- **Next session start:** read A2_IDENTITY_DESIGN.md and the owner's answers; revive Fabric per FABRIC_RUNBOOK section 2; run `scripts/live/live_phase3.sh` then `live_phase2.sh` as smoke tests.

## 2026-09-22 — Session 4 (chaincode G2 + real FabricLedgerService built and verified on Fabric; STOP before Phase 3)
- **Did:** C-08/C-09 added; Phase 2 Spring side committed (`9e159b5`, tag `phase2-spring-done`; pushed 2026-09-22 with `phase2-done`); Go chaincode `evidence` (22 tests) deployed as v1.1 seq 2 on the REVIVED network; real `FabricLedgerService`; 130 Java tests; the whole Phase 2 checklist re-run through real Fabric (identical to the reference run) plus Fabric-only checks (real txIds, MVCC race, peer outage, restart persistence); runbook and scripts saved in the repo.
- **Left:** Owner review of the chaincode stage; then Phase 3 (D1-D3 status machine + two-step custody, E1-E3 cases, A2 per-user Fabric identities which closes C-08). **Update: committed as `7b814eb`, tag `phase2-done`, pushed to origin.**
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


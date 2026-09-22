# Handover

<!-- Most recent session first. 5 lines per entry: date + did / left / broken / watch out for. -->

## 2026-09-22 — Session 8 (Phase 5: F4 audited; F2/F3 and F5 built, verified live on real Fabric)
- **Did:** audited F4 (no C-06 violation; one accepted residual - free-text reason/note fields could carry
  personal data, undocumented before, D-058). Designed F2/F3 (docs/F2_F3_ENVELOPE_ENCRYPTION_DESIGN.md, approved
  with C-10 added first) and built it: `crypto/` package (AES-256-GCM content keys, RSA-2048 per-user wrapping,
  Flyway V6), ledger hashes now cover ciphertext so `VerificationService` needed zero changes, new `/file`
  download endpoint, `addMember`/`removeMember` re-wrap/revoke, transfer-receiver auto-wrap in `EventProcessor`.
  Live-verified end to end against real Postgres+IPFS (encrypt-on-register, decrypt-for-authorised, 403 for
  others, add/remove-member access changes, download round-trip, tamper detection) first against
  `memory-ledger`, then - after the owner approved reissuing the lost Fabric CA registrar secret and the dev
  users' wallet secrets (D-060/D-061, same "identity modify" recovery pattern `bootstrap_registrar.sh` already
  documents, no new access created) - against REAL Fabric: a real chaincode write and the custody-transfer-
  receiver auto-wrap off G3's real event stream both confirmed (D-062). **All of F2/F3 verified against real
  Fabric, real PostgreSQL and real IPFS.** Then built F5 (owner-approved deviations: a plain Java retry loop
  instead of the spring-retry dependency, and scoped to `register()`'s `createEvidence` only - `update`/status/
  transfer/disposal are NOT retried, since a genuine MVCC conflict there needs a re-read-rebuild step F5 didn't
  ask for, not a blind resubmit; D-063). Then built I1 (chain-of-custody PDF, owner design point confirmed
  first: `ReportService` depends ONLY on `LedgerService`/`VerificationService`, architecturally incapable of
  needing decrypted content - new dependency OpenPDF 2.2.2, not iText, D-064); found and fixed a real layout bug
  (long tx-ids/actor UUIDs wrapping mid-string in a cramped table, caught by reading the generated PDF back with
  `PdfTextExtractor` in a unit test) before the live run. 235 Java tests total (18 F2/F3 + 6 F5 + 3 I1, all new
  tests against real crypto, a controlled failure sequence, or a real reference-ledger timeline - none
  mocked-away). Committed: `9445edf` (F4 + F2/F3 build), `a656a52` (F2/F3 real-Fabric docs), `e361dce` (F5); I1
  not yet committed - see Left.
- **Left:** I1's code/docs are uncommitted (pending this session's own review pass); L1-L3, K2 not started.
- **Fixed mid-session, not a standing issue:** the first real-Fabric attempt 500'd with `AEADBadTagException` -
  a fresh random master key had been generated for that run, but the collector's RSA private key was already
  persisted in Postgres under the EARLIER run's master key; fixed by reusing the same key (it must stay stable
  across restarts against the same database, exactly like the JWT secret should not for auth but the master key
  must for already-encrypted data).
- **Watch out:** dev users' login passwords were reset via direct SQL to a new value during this session (the
  original seed password was unknown/lost the same way the wallet was); their Fabric CA `maxenrollments` is now
  `-1` (unlimited), a deliberate deviation from A2's original `1` (D-061) so a future lost wallet does not
  reproduce this same recovery scramble - re-enrolling once more only needs `identity modify --secret`, not this
  session's two-step recovery. The scratch wallet/env files used for this session's verification are in this
  session's scratchpad only, not the repo.
- **Next session start:** proceed to L1-L3 (unit test gaps, Testcontainers, Docker Compose) then K2
  (springdoc/Postman) last, per the owner's own sequencing. No outstanding Fabric/wallet blocker remains.

> **Date correction (2026-09-22, local IST):** sessions 3 and 4 were first dated 2026-09-23/24 and 2026-09-25 in several docs, dates I inferred without reading a clock. The machine clock and every log/ledger timestamp show all of this work happened on the night of 2026-09-21 (UTC) / 2026-09-22 (IST). All doc dates were corrected to 2026-09-22; the UTC timestamps inside logs and on the ledger were never touched.

## 2026-09-22 — Session 7 (Phase 4 complete: G3, H1-H4, A6 all built and verified live)
- **Did:** designed G3's consistency model (docs/G3_SYNC_DESIGN.md, approved, then extended with an explicit reconnect/backoff strategy per the owner's follow-up) and built it: `evidence_activity`/`evidence_projection`/`ledger_sync_checkpoint` (Flyway V4), idempotent (UNIQUE tx_id) and replay-safe (Postgres-backed checkpoint, empty = replay from block 0), two retry tiers with a new `eventSync` health signal; committed separately (52bc05b) after live-verifying a first-run backfill (164/90 rows), a live write, and - the owner's required check - two writes made directly to the chaincode while the whole app was stopped, both caught up with zero duplicates on restart. Then built H1 (search), H2 (dashboard), H3 (activity feed), H4 (notifications, incl. a live tamper alert), and A6 (audit log) straight through per the design's section 11, all reading only from G3's schema; A6 specifically verified the owner's required scenario - a real user deactivated mid-session while their access token stayed valid produced a distinct `TOKEN_USED_AFTER_DEACTIVATION` audit row, not an ordinary VIEW, shown in the running system. 208 Java tests total.
- **Left:** this whole session's work (G3 + H1-H4/A6) is committed locally but UNPUSHED (two commits: 52bc05b G3, and one more for H1-H4/A6 - see `git log`); Phase 5 (I1, F2-F5, L1-L3, K2) has not been started, per the owner's "stop and show before Phase 5" instruction.
- **Broken:** nothing known. KNOWN GAPS unchanged from Session 6 (single-org endorsement/orderer outage/peer failover/load, owner-deferred; A2 residuals) plus new Phase 4 ones: single event-sync-listener instance only, one GetHistory read per event, H1 free-text search matches only the CURRENT reason (not history), A6 covers evidence view/download only (not every endpoint) and has no X-Forwarded-For handling, `audit_log`/`notifications` have no retention policy, H4 email is out of scope.
- **Watch out:** there is still no A4 (admin user management) endpoint - the A6 deactivation check used a direct SQL `UPDATE users SET enabled=false`, which is fine for verification but means a real deploy has no way to deactivate a user through the API yet; G3's listener and its health indicator (`eventSync`) are `!memory-ledger`-scoped, same as A2's wallet - they do not exist under the reference-ledger profile.
- **Next session start:** read `docs/G3_SYNC_DESIGN.md` section 8 (what G3 does not solve) and `docs/KNOWN_GAPS.md` before Phase 5; revive Fabric per FABRIC_RUNBOOK section 2, re-run `scripts/live/live_phase2.sh` as a smoke test (same wallet env vars as Session 6).

## 2026-09-22 — Session 6 (Phase 3 fully closed: pushed phase3-done, built E3, designed AND deployed A2)
- **Did:** pushed `phase3-done` (202ba64) to origin; built E3 (Option B, owner's choice: `case_evidence` link table, `EvidenceService.register` now requires an existing case, unconditional) and committed it separately (0bde57c); built and deployed A2 (owner-approved A2-Q1..Q8): chaincode `authorise()` now certificate-authoritative (`evidence` v1.3 seq 4, deployed only after enrolling all 6 dev users, per the new mandatory ordered step in `docs/FABRIC_RUNBOOK.md` section 8), `FabricLedgerService` signs writes with the actor's own wallet identity (`IdentityStore`/`FileWalletIdentityStore`), reads unchanged; operator scripts `scripts/fabric/{bootstrap_registrar,enroll_users}.sh`; C-08 reworded (not "closed", per A2-Q7's exact text) in CONSTRAINTS.md. Verified live at every stage: pre-cutover per-user signing (qscc creator match), the peer-CLI identity-forgery checks (User1/mismatched-cert refusals), and the whole Phase 2 checklist unchanged post-cutover. 183 Java + 36 Go tests. Two bugs found live and fixed: SunEC-vs-BC EC key encoding in tests, and BouncyCastle needing provider registration (not just classpath presence) to decrypt wallet keys.
- **Left:** E3 and A2 work is UNCOMMITTED (revert point: tag `phase3-done`, already pushed); owner should review and say whether to commit/tag/push (e.g. `phase4-ready`) before Phase 4 starts, per the "stop and show" instruction for this stage.
- **Broken:** Nothing known. **KNOWN GAPS unchanged from Session 5 (single-org endorsement failure, orderer outage, peer failover, load — owner-deferred) plus new A2 residuals: backend still custodies user keys (compromised-host risk not solved by A2, by design), no peer-side certificate revocation, registrar credential exists offline, no user-lookup endpoint, status not custodian-only, E3's off-chain link write is not transactional with the ledger write.**
- **Watch out:** the A2 chaincode cutover cannot be undone once deployed (`docs/ROLLBACK.md` top section, written BEFORE the deploy as required); a 7th user (once A4 exists) MUST be enrolled (`scripts/fabric/enroll_users.sh`) before their first write or they get 403 LEDGER_IDENTITY_MISSING; inline multi-statement `wsl -d Ubuntu -- bash -c '...; ...'` invocations unreliably lose exported variables between statements in this environment — always write Fabric CLI logic to a script FILE and invoke that file, never inline it (this cost real time twice this session).
- **Next session start:** read `docs/A2_IDENTITY_DESIGN.md` §4 residuals and `docs/KNOWN_GAPS.md` before Phase 4; revive Fabric per FABRIC_RUNBOOK section 2, re-run `scripts/live/live_phase2.sh` as a smoke test (wallet env vars: `FABRIC_WALLET_DIR`, `FABRIC_WALLET_PASSPHRASE`).

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


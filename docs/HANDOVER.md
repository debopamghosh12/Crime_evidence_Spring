# Handover

> **This file has two parts.** The first, below, is a standing END-OF-PROJECT SUMMARY written when Phase 5
> (K2) closed the last item of the 5-phase build order — read this first, it is the viva prep sheet. The
> second part, further down, is the ORIGINAL SESSION LOG (most-recent-first, 5 lines per entry) kept intact
> as the detailed, chronological record of how each decision and bug actually happened.

---

# END-OF-PROJECT SUMMARY (as of 2026-09-22, after K2)

## 1. What was built

All 5 phases of FEATURE_LIST.md's own "Suggested build order" table are complete. That order was designed to
cover every P0 feature (24 of them) plus the P1/P2 features needed for a credible, demoable final-year system;
it is **not** the same as all 58 features in FEATURE_LIST.md (see §2 and §3 for what's out of scope).

| Phase | Features | Status | Key commits (tags) |
|---|---|---|---|
| 1 - Foundation | K1, K3, A1, A3, G1, F1, G4 | Done | `c1a4d4b` (`phase1-done`, pushed) |
| 2 - Core evidence | B1-B5, C1-C3, G2 | Done | `9e159b5`/`7b814eb` (`phase2-done`, pushed) |
| 3 - Custody and cases | D1-D3, E1-E3, A2 | Done | `0bde57c` (E3), A2 deployed live, (`phase3-done`, pushed) |
| 4 - Off-chain sync | G3, H1-H4, A6 | Done | `52bc05b` (G3), `92df666` (H1-H4/A6) |
| 5 - Finish | I1, F2-F5, L1-L3, K2 | Done | `9445edf`, `a656a52`, `e361dce`, `d071c4e`, `136920e`, `1e32604`, `50088b2` |

Highlights, briefly, phase by phase:

- **Phase 1** — JWT login + BCrypt (A1), role-checked method security (A3), the `LedgerService` interface that
  every later phase built behind (G1) so the Fabric implementation is swappable in principle, IPFS client (F1)
  and Actuator health (G4), plus the global validation/error-format contract (K1) and profile-based config (K3).
- **Phase 2** — evidence registration with file upload and SHA-256 hashing (B1, B2, C1), versioned updates that
  never overwrite history (B4), archive/dispose replacing DELETE entirely (B5, so **evidence is never deleted**
  in this system — C-02/CLAUDE.md), the `verify` endpoint that re-hashes and compares against the ledger (C2),
  and the real Go chaincode `evidence` (G2) that everything from here on actually writes to.
- **Phase 3** — the status state machine (D1), two-step custody transfer with a pending list for the receiver
  (D2), full custody timeline (D3), case management and officer assignment (E1-E2), evidence linked to a real
  case entity instead of a free string (E3), and A2 — every ledger write is now signed with the calling user's
  own Fabric-CA-issued identity, with the chaincode itself certificate-authoritative about who that is (not a
  role string the backend could lie about).
- **Phase 4** — a ledger event listener that syncs every chaincode write into Postgres idempotently and
  replay-safely (G3), and everything built on top of that sync: search/filter (H1), dashboard analytics (H2),
  an activity feed (H3), notifications including live tamper alerts (H4), and an access audit log that
  distinguishes a normal view from `TOKEN_USED_AFTER_DEACTIVATION` (A6).
- **Phase 5** — envelope encryption: every file gets its own AES-256-GCM content key, wrapped RSA-2048-OAEP
  per authorised user, so a public IPFS CID alone reveals nothing (F2/F3); upload retry on genuine Fabric MVCC
  conflicts (F5); a chain-of-custody PDF report built from ledger data alone, so it needs no content-decryption
  access at all (I1); real unit-test gaps filled rather than padded for a coverage number (L1); Testcontainers
  integration tests against a real Postgres (L2); a one-command Docker Compose demo for backend+Postgres+IPFS
  (L3, Fabric deliberately kept as a documented separate WSL step — see §2); and full, annotation-driven Swagger
  documentation plus a verified-live Postman collection covering every endpoint (K2).

Every phase above was **verified live**, not just unit-tested — against a real Fabric network (except where §2
notes otherwise), real PostgreSQL, and a real IPFS node — with the actual commands and actual output captured
in `docs/TEST_CHECKLIST.md` and one `docs/features/<name>.md` write-up per feature (full list: `docs/features/`,
32 files). 255 automated Java tests pass as of the last commit (`50088b2`).

## 2. Known limitations

These are accepted, documented gaps — not oversights found late. Full detail: `docs/KNOWN_GAPS.md`.

- **Fabric is not containerized (L3).** An owner-requested risk assessment (grounded in reading
  `fabric-samples/test-network/network.sh`'s own 684-line, multi-stage, retry-looped bootstrap, and this
  project's own repeated Fabric CA/wallet fragility) concluded real risk of sinking time into a fragile
  containerized Fabric setup for a final-year-project timeline. Fabric stays WSL-hosted, started separately
  per `docs/FABRIC_RUNBOOK.md`; `docker compose up` alone covers backend+Postgres+IPFS.
- **The `memory-ledger` reference profile has no persistence.** A backend container/process restart loses all
  evidence data under that profile (Postgres-native data — users, cases — survives). It exists for fast local
  development and CI-shaped tests, not as a production ledger; real Fabric has no such limit.
- **The server-held master key (F2/F3, C-10) is a single point of compromise** for every user's content key.
  No HSM or key rotation is in scope. If the master key is exposed, every encrypted file's confidentiality is
  defeated, though the ledger's tamper-evidence (hashes over ciphertext) is unaffected.
- **A2 solves per-user *signing* identity, not key custody.** The backend still holds every user's RSA private
  key (for F2/F3 unwrapping) and Fabric wallet identity; a compromised backend host can still act as any user.
  This was a known, named residual of A2's design from the start, not a regression.
- **Single-instance assumptions**: the G3 ledger-event listener has no clustering/coordination (would race on
  its Postgres checkpoint if ever run more than once); F5's retry covers only `register()`'s `createEvidence`
  call, not `update`/status/transfer/disposal (those need a re-read-rebuild step on conflict, not a blind
  resubmit, which F5 was not scoped to build).
- **Search and audit have narrower coverage than their names suggest**: H1's free-text search matches only the
  *current* version's reason field, not history; A6's audit log covers evidence view/download and
  authentication events, not literally every endpoint; neither `audit_log` nor `notifications` has a retention
  policy.
- **No CI pipeline (L4)** — the 255-test suite and the Postman/Newman collection are both run manually, not on
  every push.
- **A4 (admin user management) and A5 (case-level read restriction) were never built** — they appear in no
  phase of the build order. Users exist only via the dev seeder; a user added once A4 existed would need the
  same Fabric enrollment step (`scripts/fabric/enroll_users.sh`) run for them before their first write.
- **Everything else in FEATURE_LIST.md outside the 5-phase build order is unbuilt by design**, not by omission:
  B6-B8 (bulk register, physical-evidence extra fields, tags/related-evidence), C4-C5 (ledger-side timestamps,
  scheduled integrity sweep), D4-D5 (mandatory-reason validation beyond what's already enforced, explicit
  current-custodian field), E4 (close/reopen cases), I2-I4 (QR labels, shareable verification link, electronic
  records certificate), J1-J3 (dispute flag and reviewer-panel voting), K4-K6 (rate limiting, CORS/HTTPS
  hardening, structured logging), L4 (CI). These are all P1/P2 "should have"/"stretch" items per
  FEATURE_LIST.md's own priority table, explicitly deferred, not silently dropped.

## 3. Future work

FEATURE_LIST.md states this project's own scoping decision up front: *"The report describes Polygon, Chainlink
VRF, a DAO Jury and Web3Auth, while the working prototype runs on Hyperledger Fabric... This list targets
Fabric... Everything sits behind a `LedgerService` interface, so a Polygon implementation with web3j can be
added later without changing the controllers."* That remains exactly true today — **nothing in this codebase
uses Polygon, a DAO, or Web3Auth**; they are report-described, not-yet-built future work, and should be
presented at the viva as such, not implied to exist:

- **Polygon L2 deployment** — a second `LedgerService` implementation (e.g. via web3j) could sit behind the
  same interface G1 already established; no controller, service, or DTO would need to change, since ledger
  calls only ever go through that interface (CLAUDE.md's own hard constraint). Genuinely feasible given the
  architecture, but zero code toward it exists.
  A DAO Jury with token staking and automatic slashing ("Legal Ticket") — J1-J3 sketch a simplified,
  backend-selected-reviewer-panel version of this on Fabric, itself unbuilt; a real DAO/on-chain-vote/slashing
  design was never attempted.
- **Chainlink VRF** for random jury selection — would only make sense once a jury/dispute mechanism (J1-J3)
  exists at all; currently neither exists.
- **Web3Auth hidden wallets** — this project uses BCrypt-hashed passwords plus JWT (A1) and CA-issued Fabric
  certificates (A2) for identity; no wallet-based or social-login auth was attempted.
- Also named in FEATURE_LIST.md's own future-work row, all equally unbuilt: a gasless relayer, Stripe fiat
  subscriptions, and deepfake detection "for the oracle problem" (relevant to a DAO jury verifying real-world
  claims, itself not built).
- Nearer-term, more mundane future work that would matter more for a production deployment than any of the
  above: A4/A5 (§2), an HSM or rotation strategy for the F2/F3 master key, a CI pipeline (L4), and containerizing
  Fabric properly once there's time to do it without risking the demo.

## 4. Where the strongest evidence is, for each P0 claim

Every P0 feature has its own `docs/features/<id>-*.md` write-up (found/scoped/tried/verified, with real command
output) unless noted otherwise below. This table is the fast index into that evidence for viva questions.

| P0 | Claim | Strongest evidence |
|---|---|---|
| A1 | JWT login, BCrypt passwords | `AuthServiceTest`, `JwtServiceTest`; `docs/features/a1-jwt-login.md`; TEST_CHECKLIST P1 |
| A2 | Per-user Fabric identity, not a backend-asserted role | `docs/A2_IDENTITY_DESIGN.md`; DECISIONS D-059/D-062; live chaincode write with per-user signing confirmed via `qscc` creator match, plus a rejected forged-identity attempt, both against real Fabric |
| A3 | RBAC, checked in Spring and the chaincode | `SecurityAndErrorFormatTest`; `Permissions` class SpEL expressions; `docs/features/a3-rbac-scaffolding.md`; chaincode-side `authorise()` (A2) |
| B1 | Register evidence, server-generated ID, metadata to IPFS | `EvidenceServiceTest`/`EvidenceControllerTest`; `docs/features/b1-register-evidence.md`; TEST_CHECKLIST P2 and P2-F (real Fabric) |
| B2 | File upload with size/type limits | `docs/features/b2-file-upload.md`; TEST_CHECKLIST P2 §"B2 upload limits and types" (real 415/400/413 responses, a real 20 MB upload verified end to end) |
| B3 | Retrieve by ID or CID | `EvidenceControllerTest`; `docs/features/b3-retrieve-evidence.md` |
| B4 | Versioned update, never overwritten | `LifecycleServicesTest`; `docs/features/b4-versioned-metadata-update.md` |
| B5 | Archive/dispose replaces delete | `docs/features/b5-archive-dispose.md`; TEST_CHECKLIST P2 disposal-flow sequence (403/409/200 real transitions, JUDGE-only approve); `DELETE` confirmed `405` |
| C1 | SHA-256 hash at upload | `HashingInputStreamTest`; `docs/features/c1-sha256-file-hash.md` |
| C2 | `/verify`, VERIFIED/TAMPERED/NOT_FOUND | `docs/features/c2-verify-endpoint.md`; TEST_CHECKLIST "F1 IPFS outage" and the Phase 2 real-tamper test (deliberate block corruption -> TAMPERED, deleted block -> NOT_FOUND) |
| C3 | Full ledger history with tx IDs | `docs/features/c3-ledger-history.md`; cross-checked independently by I1's PDF report matching `/history` exactly |
| D1 | Status state machine, invalid jumps rejected | `LifecycleServicesTest`; `docs/features/d1-status-state-machine.md` |
| D2 | Two-step custody transfer | `InMemoryLedgerTransferTest`, `Phase3ControllersTest`; `docs/features/d2-custody-transfer.md`; `scripts/live/live_phase3.sh` against real Fabric |
| D3 | Custody timeline with tx IDs | `docs/features/d3-custody-timeline.md`; I1's PDF report (independent cross-check against the same data) |
| E1 | Case management | `CaseServiceTest`; `docs/features/e1-e2-cases-and-officers.md` |
| E3 | Evidence linked to a real case entity | `docs/features/e3-evidence-case-link.md`; `EvidenceService.register` now requires an existing case (enforced, not advisory) |
| F1 | IPFS integration, survives fetch failure | `HttpIpfsClientTest`; `docs/features/f1-ipfs-client.md`; TEST_CHECKLIST "F1 IPFS outage" (ledger info survives, verify returns 503 not NOT_FOUND) |
| G1 | `LedgerService` abstraction | `docs/features/g1-ledger-service.md`; `FabricLedgerServiceTest` + `InMemoryLedgerServiceTest` (two real implementations behind one interface, proving the abstraction actually holds) |
| G2 | Chaincode with role checks and status validation | `docs/features/g2-chaincode.md`; Go test suite (`evidence_test.go`, a fake-ledger harness); deployed and live-verified at each version (v1.1 seq 2 -> v1.2 seq 3 -> v1.3 seq 4) |
| H1 | Search/filter/sort/paginate from Postgres | `SearchServiceTest`; `docs/features/h1-h4-search-dashboard-feed-notifications.md` |
| K1 | Bean Validation + one global error format | `SecurityAndErrorFormatTest`; `docs/features/k1-error-format.md` |
| K2 | OpenAPI/Swagger + Postman collection | `docs/features/k2-api-documentation.md`; DECISIONS D-068; TEST_CHECKLIST P5-K2 (Swagger UI zero console errors live in a browser; `/v3/api-docs` valid OpenAPI 3.1 with 0 missing summaries; 42/42 Postman requests via Newman, 3 consecutive clean runs) |
| K3 | Config/profiles, no secrets in code | `docs/features/k3-configuration-and-secrets.md`; `application.yml` profiles, `.env.example` |
| L1 | Unit tests across services/state-machine/RBAC/hashing | DECISIONS D-065; TEST_CHECKLIST P5-L1; 255 total automated tests, 0 failures, as of the final commit |

Two P0-adjacent notes worth having ready for the viva: **B5's "no delete" claim is a hard constraint, not just a
feature** — `DELETE /api/evidence/{id}` returns `405` for every role, verified explicitly (C-02, CLAUDE.md).
And **C2/I1 together are the strongest demonstration of the encryption design's own soundness**: `/verify` and
the PDF report both operate on ledger/hash data only and were confirmed, live, to produce identical results for
a user with no content-decryption access as for one who has it — proving F2/F3's confidentiality layer doesn't
compromise the tamper-evidence layer it sits on top of.

---

# ORIGINAL SESSION LOG (most recent first)

<!-- 5 lines per entry: date + did / left / broken / watch out for. -->

## 2026-09-22 — Session 8 (Phase 5 built straight through: F4, F2/F3, F5, I1, L1-L3, K2 — ALL FIVE PHASES NOW COMPLETE)
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
  `PdfTextExtractor` in a unit test) before the live run. Then L1: surveyed every untested main class, filled 3
  genuine pure-logic gaps (`SearchService` sort allow-list, `DashboardService`'s 3 JDBC row-shape branches,
  `ActivityService`'s caseId/page-size branching, D-065) - deliberately did NOT add Criteria-API-mocking tests
  for `EvidenceProjectionSpecifications`/`AuditSpecifications` (that needs a real database to mean anything,
  which is what L2 is for) or more `VerificationService.overall()` coverage (already strong via
  `EvidenceServiceTest`). Then did the owner-requested Fabric-in-Compose risk assessment BEFORE writing any
  Compose code: grounded in `network.sh`'s own 684-line, multi-stage, retry-looped bootstrap plus this session's
  A2 CA/wallet fragility, concluded real risk; owner approved the fallback. Built L2 (Testcontainers Postgres,
  mocked Fabric/IPFS via the already-existing `memory-ledger`/`FakeIpfsClient` - rejected WireMock, redundant
  with `HttpIpfsClientTest`'s existing JDK-`HttpServer` coverage, D-066) and L3 (`Dockerfile` +
  `docker-compose.yml` for backend+Postgres+IPFS, Fabric left a documented separate WSL step, D-067) together.
  Verified L3 with a genuine cold start (`docker compose down -v` then `up --build`, 3m31s, all containers
  healthy, register/encrypt/verify all worked inside the containers) and found, live, a real limit: a backend
  container restart loses all `memory-ledger` evidence data (Postgres data survives) - documented in the compose
  file itself. Finally built K2: springdoc-openapi 2.8.6, real (not stub) `@Operation`/`@Tag` annotations
  documenting F2/F3 encryption, the two-step transfer, disposal approval, and verify/report's no-content-key
  property; rebuilt the Postman collection from scratch to cover every live endpoint across all 5 phases (42
  requests); found and fixed 5 real bugs by actually running it via Newman (D-068). Verified live: Swagger UI in
  a real browser (zero console errors), `/v3/api-docs` valid with 0 missing summaries, 3 clean Newman runs
  (42/42, 0 failed, 20/20 assertions). 255 Java tests total, full regression re-run clean immediately before the
  K2 commit. Committed: `9445edf` (F4+F2/F3), `a656a52` (F2/F3 real-Fabric docs), `e361dce` (F5), `d071c4e`
  (I1), `136920e` (L1), `1e32604` (L2/L3), `50088b2` (K2). **This closes Phase 5 and the full 5-phase build
  order - see the END-OF-PROJECT SUMMARY at the top of this file.**
- **Left:** nothing in the 5-phase build order. Out-of-scope future work (A4/A5, L4/CI, Fabric-in-Compose,
  master-key HSM/rotation, Polygon/DAO/Web3Auth) is listed in the END-OF-PROJECT SUMMARY §2-§3, not "left" in
  the sense of blocking anything - it was never in scope for this build order.
- **Fixed mid-session, not a standing issue:** the first real-Fabric F2/F3 attempt 500'd with
  `AEADBadTagException` - a fresh random master key had been generated for that run, but the collector's RSA
  private key was already persisted in Postgres under the EARLIER run's master key; fixed by reusing the same
  key (it must stay stable across restarts against the same database, exactly like the JWT secret should not
  for auth but the master key must for already-encrypted data).
- **Watch out:** dev users' login passwords were reset via direct SQL to a new value during this session (the
  original seed password was unknown/lost the same way the wallet was); their Fabric CA `maxenrollments` is now
  `-1` (unlimited), a deliberate deviation from A2's original `1` (D-061). The scratch wallet/env files and the
  filled-in Postman environment (real `devSeedPassword`) used for live verification are in this session's
  scratchpad only, never committed - the repo's `postman/BlockEvidence.postman_environment.json` keeps a
  `REPLACE_ME` placeholder.
- **Next session start:** no outstanding blocker. If extending this project further, read the END-OF-PROJECT
  SUMMARY §2-§3 first for what's deliberately out of scope and why, before assuming something is a bug.

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

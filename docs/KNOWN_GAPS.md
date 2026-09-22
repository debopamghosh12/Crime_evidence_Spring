# Known gaps and limitations (report-facing)

**Purpose.** An honest list of what is NOT tested, NOT built, or only partly true, so the report and the demo claim nothing that
was not done (FEATURE_LIST.md: "keep an honest split so nothing is claimed that is not built"). Every entry names where the detail
lives. Last updated 2026-09-22 (Phase 3 and Phase 4 both complete: G3, H1-H4, A6 all built, verified live and owner-approved). Update this file whenever a gap is closed or a new one is found; never delete an
entry silently, mark it resolved with the date.

## A. Deliberately left UNTESTED (owner decision 2026-09-22)

These were considered, judged out of scope for the final-year deliverable, and are stated as known gaps rather than dropped.

| Gap | What is not known | Why it matters | Detail |
|---|---|---|---|
| **Single-org endorsement failure** | What the backend returns when one of the two organisations' peers is down or refuses to endorse (the channel policy needs both) | Writes would fail; we have not observed the exact error mapping | TEST_CHECKLIST P2-F.9; the gateway's error text for this case has never been fed to `FabricErrors` |
| **Ordering service (orderer) outage** | Behaviour of `submitTransaction` while `orderer.example.com` is down, including timeouts and whether a submitted write can be committed later than the API reported | A write could time out at the API yet still commit afterwards | P2-F.9; `submitTimeout` and `commitTimeout` are set but were never exercised |
| **Peer failover** | The backend talks to ONE peer (`peer0.org1`). There is no failover to Org2's peer | One peer down = ledger unavailable (observed: 503, recovers when the peer returns) | P2-F.7 F3, ARCHITECTURE section 12 |
| **Load and concurrency at scale** | Throughput, latency under load, memory for concurrent 50 MB uploads (only one 20 MB upload timed) | Unknown capacity; the 2 s block batch timeout bounds write latency | P2-F.9, TEST_CHECKLIST P2.4, B2 write-up |

## B. Security and trust gaps

| Gap | Status | Detail |
|---|---|---|
| **C-08: the chaincode trusts the role the backend passes** (not authenticated) | **REVISED 2026-09-22 (A2), not fully closed.** The chaincode now authenticates user+role from a Fabric CA certificate, and a forged argument is refused - but the backend still custodies every user's private key, so a compromised backend host can sign as any enrolled user | CONSTRAINTS.md C-08 (reworded), A2_IDENTITY_DESIGN.md section 4, TEST_CHECKLIST Appendix P3-A2 |
| **A2 residuals, not closed by design:** registrar credentials exist offline (their loss lets an attacker enroll `role=JUDGE` users); no peer-side certificate revocation (no channel CRL configured, so a revoked-at-the-CA identity still works until its certificate expires); role/attribute changes need re-enrollment; certificate expiry (1 year) and renewal are operational, not automatic | OPEN by design | A2_IDENTITY_DESIGN.md section 4, `docs/DECISIONS.md` D-047..D-050 |
| **IPFS content is not private.** A default node joins the public network; even `--offline` stores files and metadata unencrypted | **CLOSED for evidence registered from 2026-09-22 onward (F2/F3).** File and metadata content is now AES-256-GCM encrypted before pinning, per-user RSA-wrapped keys (F3). Earlier-registered evidence is NOT retroactively encrypted | docs/features/f2-f3-envelope-encryption-key-management.md, DECISIONS D-020/D-058/D-059, CONSTRAINTS C-09/C-10 |
| **F2/F3's master key is a single point of compromise for every user's content key** (by design, same trust tier as C-08) | OPEN, accepted (no HSM/rotation in scope) | CONSTRAINTS C-10 |
| ~~F2/F3 was verified against `memory-ledger`, not real Fabric~~ | **RESOLVED 2026-09-22.** The lost Fabric CA registrar credential and dev-user wallet were rebuilt (owner-approved, D-060/D-061); both previously-unconfirmed checks (a real chaincode write, the custody-transfer-receiver auto-wrap off G3's real event stream) now pass live | DECISIONS D-059/D-060/D-061/D-062, docs/features/f2-f3-envelope-encryption-key-management.md |
| **Free-text ledger fields (`reason`, `note`/`notes` on status change, disposal, transfer) are length-checked only, never content-checked.** A user could type personal data into one and it becomes permanently immutable. Not a C-06 violation (the system never adds personal data itself), but a real human-input residual, found by the F4 audit | OPEN, accepted (content-scanning free text is out of scope) | docs/features/f4-personal-data-off-chain-audit.md |
| Access tokens outlive user deactivation by up to 15 min; refresh tokens are not purged | OPEN | ARCHITECTURE section 10 |
| Read access is not limited by case (A5 unscheduled): any authenticated role reads any evidence | OPEN | D-019, B3 write-up |
| Upload type check trusts the client-declared content type; no content sniffing or malware scan | OPEN | D-026 |
| No rate limiting (K4), no HTTPS/CORS/headers hardening (K5) | NOT BUILT (later phases) | FEATURE_LIST.md |

## C. Integrity and operations gaps

| Gap | Status | Detail |
|---|---|---|
| **Orphaned IPFS pins** are possible when a register fails during a ledger outage (compensation refuses to unpin what it cannot confirm) | ACCEPTED by design; no reconciliation sweep | DECISIONS D-024, D-037 |
| ~~No automatic retry on Fabric MVCC conflicts (F5)~~ | **BUILT 2026-09-22**, scoped to `register()`'s `createEvidence` call only (the one ledger write with no contended key, confirmed by reading the chaincode) - `update()`/status/transfer/disposal calls are NOT retried on a genuine MVCC conflict, since blindly resubmitting the same `expectedVersion` cannot help there (a meaningful retry would need a re-read-rebuild step per call site, not built) | D-063, docs/features/f5-upload-retry.md |
| A genuine Fabric-level MVCC conflict could not be naturally triggered live for F5's verification - `CreateEvidence` has no contended key by design, so this is verified by 6 focused unit tests with a controlled ledger double, not a live trigger | ACCEPTED, documented rather than contrived | D-063 |
| No scheduled integrity sweep (C5); verification is on demand only | NOT BUILT (stretch) | C2 write-up |
| Chaincode events now consumed and synced to Postgres (G3, 2026-09-22); H1-H4 (search, dashboard, feed, notifications) still read nothing from the resulting schema yet | G3 BUILT, H1-H4 NOT BUILT | docs/features/g3-ledger-event-sync.md |
| Ledger test data is permanent (Fabric ledgers cannot be edited): case ids `FAB-DIRECT`, `FAB-WIRE`, `FAB-LIVE-*`; channel height 48 -> 93 | ACCEPTED; recreating the network removes it | FABRIC_RUNBOOK section 7 |
| Certificate expiry/rotation on the Fabric side, channel-level revocation lists | NOT ADDRESSED | A2 design |
| ~~No automated integration test against a real PostgreSQL in CI~~ | **PARTIALLY CLOSED 2026-09-22 (L2):** `EvidenceRegistrationIntegrationTest` runs the main flow against a real Testcontainers Postgres, mocked Fabric/IPFS. Still not wired into a CI pipeline - no CI at all (L4) is still NOT BUILT | D-066, docs/features/l2-l3-testcontainers-and-compose.md |
| **Fabric is not part of `docker-compose.yml` (L3)** - deliberately, after an owner-requested risk assessment found real risk in reproducing Fabric's own multi-stage, retry-looped bootstrap reliably for a genuine cold start. Stays a documented separate WSL step | ACCEPTED by design, owner-approved | D-067, FABRIC_RUNBOOK |
| **`memory-ledger`'s demo mode has no persistence**: a `docker compose` backend restart loses all evidence/ledger data (Postgres-native data - users, cases - survives). Found live during L3 verification | ACCEPTED, documented in `docker-compose.yml` itself and the feature doc, not just here | D-067 |

## D. Functional gaps (features not yet built, from FEATURE_LIST.md)

Phase 3 built: D1, D2, D3, E1, E2, E3, A2 (2026-09-22 - all of Phase 3). Still open: D4 (optional). Phase 4 COMPLETE: G3, H1, H2, H3, H4, A6 (2026-09-22). Phase 5 in progress: F4 audited (2026-09-22, no violation found); F2, F3 built and fully verified live against real Fabric/PostgreSQL/IPFS (2026-09-22); F5 built (2026-09-22, scoped to register() only, see section C); I1 built and verified live on real Fabric (2026-09-22, docs/features/i1-chain-of-custody-report.md); L1 done (2026-09-22, D-065, 3 real gaps filled, 253 tests); L2/L3 done (2026-09-22, D-066/D-067, Fabric-in-Compose risk assessment done first, owner approved the fallback - Compose for backend+Postgres+IPFS only, Fabric stays a documented separate WSL step); K2 still open. A4 (admin user management) and A5 (case-level access) appear in no build phase; users exist
only through the dev seeder, all 6 of whom are now A2-enrolled (`scripts/fabric/enroll_users.sh`); a user added once A4 exists
would need the same enrollment step run for them before their first write (docs/FABRIC_RUNBOOK.md section 8).

G3 specifics:
- **Single active listener instance:** no coordination (e.g. a Postgres advisory lock) for running more than one;
  fine at this project's scale, would race on the checkpoint if ever run clustered.
- **One `GetHistory` ledger read per event**, cost growing with an item's version count over its lifetime; a
  chaincode function returning one specific version would be more efficient but does not exist (out of scope, touches the chaincode).
- **The `eventSync` health DOWN threshold (5 consecutive failures) is a fixed constant**, not configurable per environment.

H1-H4/A6 specifics, all found while building them:
- **H1's free-text search matches only the CURRENT `evidence_projection.last_reason`**, not any earlier version's reason
  (found live, DECISIONS D-054) - the projection is a current-state table by design.
- **A6's VIEW/DOWNLOAD auditing covers evidence access only** (`GET /api/evidence/{id}` and its verify variants), not
  every read endpoint (search/dashboard/activity/cases).
- **A6's IP address has no `X-Forwarded-For` handling**; behind a reverse proxy every entry would show the proxy's address.
- **`audit_log` and `notifications` have no retention/purge policy**; both grow without bound, same class of gap as
  `refresh_tokens` (ARCHITECTURE section 10).
- **H4 email notifications are not built** (explicitly out of scope, no mail server configured, per the owner).


Phase 3 specifics, all found while building it:
- **No user-lookup endpoint:** a client must already know a receiver's user id to start a custody transfer, and the custody timeline shows
  ids, not names.
- **Status changes are not restricted to the custodian:** COLLECTOR, FORENSIC_ANALYST and PROSECUTOR may each move any item along the
  legal line (D-040). Per-step or custodian-only rules are an owner decision.
- **Pending transfers never expire.**
- **No endpoint closes a case** (status OPEN/CLOSED exists in the schema); no case-level read restriction (A5).
- **E3's off-chain link write is not transactional with the ledger write** (docs/features/e3-evidence-case-link.md): if it fails after
  the ledger write already succeeded, the evidence stays correct on the ledger but is missing from the case's off-chain index until
  reconciled; there is no reconciliation sweep (same class of gap as orphaned IPFS pins, D-024/D-037).
- **Custody events show opaque user ids;** only the Phase 2 write txIds were individually looked up in the peers' ledger (qscc), not each Phase 3 step.
- **No automated real-PostgreSQL test:** the case bug found live (docs/bugs/case-lead-change-unique-violation.md) is exactly what mocked
  repositories cannot show; live scripts cover it, CI does not.

## E. What was verified vs what was not (for the report's honesty statement)

- **Verified live on real infrastructure:** registration with streaming hash, on-disk corruption detection (TAMPERED) and missing
  content (NOT_FOUND), versioned updates, disposal instead of delete, history with real Fabric transaction ids, concurrent-update
  safety (exactly one winner), ledger outage handling and recovery, restart persistence, the chaincode's own role and state checks
  (driven directly through the peer CLI); custody transfer, status changes, cases, evidence-case links; A2 - every write now
  signed with the actor's own certificate (`qscc` shows the correct creator), the pre-A2 identity and mismatched-certificate
  cases refused through the peer CLI with real enrolled identities (TEST_CHECKLIST Appendix P3-A2). G3 - a first-ever run backfilling the
  whole ledger from block 0, writes made directly to the chaincode while the application was fully stopped and
  correctly caught up (with no duplicates) on restart, a redundant restart producing no double-processing.
- **Verified only against a fake or in-memory stand-in:** chaincode events; the Java service against an unavailable orderer/other org.
H1-H4/A6 (Phase 4 complete) - search/filter/pagination, dashboard aggregates, the activity feed, notifications for a
  transfer receiver, case members and a real live tamper alert, and A6's full set including the specific
  deactivation-visibility demonstration the owner required (a real user deactivated mid-session, their still-valid
  token's request produces a distinct TOKEN_USED_AFTER_DEACTIVATION row, not an ordinary VIEW).
- **Not verified at all:** section A above, the A2 residuals in section B, and everything in sections B-D marked OPEN or NOT BUILT.

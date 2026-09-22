# Known gaps and limitations (report-facing)

**Purpose.** An honest list of what is NOT tested, NOT built, or only partly true, so the report and the demo claim nothing that
was not done (FEATURE_LIST.md: "keep an honest split so nothing is claimed that is not built"). Every entry names where the detail
lives. Last updated 2026-09-22 (Phase 3 complete; Phase 4 G3 built and owner-approved, H1-H4/A6 not yet). Update this file whenever a gap is closed or a new one is found; never delete an
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
| **IPFS content is not private.** A default node joins the public network; even `--offline` stores files and metadata unencrypted | OPEN. Mitigated for dev by `--offline` (C-09). Real protection needs a private swarm or envelope encryption (F2, stretch) | DECISIONS D-020, CONSTRAINTS C-09 |
| Access tokens outlive user deactivation by up to 15 min; refresh tokens are not purged | OPEN | ARCHITECTURE section 10 |
| Read access is not limited by case (A5 unscheduled): any authenticated role reads any evidence | OPEN | D-019, B3 write-up |
| Upload type check trusts the client-declared content type; no content sniffing or malware scan | OPEN | D-026 |
| No rate limiting (K4), no HTTPS/CORS/headers hardening (K5) | NOT BUILT (later phases) | FEATURE_LIST.md |

## C. Integrity and operations gaps

| Gap | Status | Detail |
|---|---|---|
| **Orphaned IPFS pins** are possible when a register fails during a ledger outage (compensation refuses to unpin what it cannot confirm) | ACCEPTED by design; no reconciliation sweep | DECISIONS D-024, D-037 |
| No automatic retry on Fabric MVCC conflicts (F5); a conflict is returned as 409 | NOT BUILT (Phase 5) | D-024 |
| No scheduled integrity sweep (C5); verification is on demand only | NOT BUILT (stretch) | C2 write-up |
| Chaincode events now consumed and synced to Postgres (G3, 2026-09-22); H1-H4 (search, dashboard, feed, notifications) still read nothing from the resulting schema yet | G3 BUILT, H1-H4 NOT BUILT | docs/features/g3-ledger-event-sync.md |
| Ledger test data is permanent (Fabric ledgers cannot be edited): case ids `FAB-DIRECT`, `FAB-WIRE`, `FAB-LIVE-*`; channel height 48 -> 93 | ACCEPTED; recreating the network removes it | FABRIC_RUNBOOK section 7 |
| Certificate expiry/rotation on the Fabric side, channel-level revocation lists | NOT ADDRESSED | A2 design |
| No automated integration test against a real PostgreSQL or a real Fabric network in CI (verification is by documented live runs); no CI at all (L4) | NOT BUILT | D-012, TEST_CHECKLIST |

## D. Functional gaps (features not yet built, from FEATURE_LIST.md)

Phase 3 built: D1, D2, D3, E1, E2, E3, A2 (2026-09-22 - all of Phase 3). Still open: D4 (optional). Phase 4 in progress: G3 built (2026-09-22); H1-H4, A6 not yet. Then Phase 5 (I1, F2-F5, L1-L3, K2). A4 (admin user management) and A5 (case-level access) appear in no build phase; users exist
only through the dev seeder, all 6 of whom are now A2-enrolled (`scripts/fabric/enroll_users.sh`); a user added once A4 exists
would need the same enrollment step run for them before their first write (docs/FABRIC_RUNBOOK.md section 8).

G3 specifics:
- **Single active listener instance:** no coordination (e.g. a Postgres advisory lock) for running more than one;
  fine at this project's scale, would race on the checkpoint if ever run clustered.
- **One `GetHistory` ledger read per event**, cost growing with an item's version count over its lifetime; a
  chaincode function returning one specific version would be more efficient but does not exist (out of scope, touches the chaincode).
- **The `eventSync` health DOWN threshold (5 consecutive failures) is a fixed constant**, not configurable per environment.


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
- **Not verified at all:** section A above, the A2 residuals in section B, and everything in sections B-D marked OPEN or NOT BUILT.

# Known gaps and limitations (report-facing)

**Purpose.** An honest list of what is NOT tested, NOT built, or only partly true, so the report and the demo claim nothing that
was not done (FEATURE_LIST.md: "keep an honest split so nothing is claimed that is not built"). Every entry names where the detail
lives. Last updated 2026-09-22 (end of Phase 2). Update this file whenever a gap is closed or a new one is found; never delete an
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
| **C-08: the chaincode trusts the role the backend passes** (not authenticated) | OPEN, closed in part by A2 (Phase 3); see the A2 design for the residual | CONSTRAINTS.md C-08, CHAINCODE_DESIGN.md section 5 and 10 |
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
| Chaincode events are emitted and unit-tested but nothing consumes them (no Postgres sync, search, dashboard, notifications: G3, H1-H4, Phase 4) | NOT BUILT | CHAINCODE_DESIGN section 6 |
| Ledger test data is permanent (Fabric ledgers cannot be edited): case ids `FAB-DIRECT`, `FAB-WIRE`, `FAB-LIVE-*`; channel height 48 -> 93 | ACCEPTED; recreating the network removes it | FABRIC_RUNBOOK section 7 |
| Certificate expiry/rotation on the Fabric side, channel-level revocation lists | NOT ADDRESSED | A2 design |
| No automated integration test against a real PostgreSQL or a real Fabric network in CI (verification is by documented live runs); no CI at all (L4) | NOT BUILT | D-012, TEST_CHECKLIST |

## D. Functional gaps (features not yet built, from FEATURE_LIST.md)

Phase 3 in progress (D1-D3, E1-E3, A2), then Phase 4 (G3, H1-H4, A6) and Phase 5 (I1, F2-F5, L1-L3, K2). Status transitions are
PROVISIONAL until D1 is finalised. A4 (admin user management) and A5 (case-level access) appear in no build phase; users exist only
through the dev seeder.

## E. What was verified vs what was not (for the report's honesty statement)

- **Verified live on real infrastructure:** registration with streaming hash, on-disk corruption detection (TAMPERED) and missing
  content (NOT_FOUND), versioned updates, disposal instead of delete, history with real Fabric transaction ids, concurrent-update
  safety (exactly one winner), ledger outage handling and recovery, restart persistence, the chaincode's own role and state checks
  (driven directly through the peer CLI).
- **Verified only against a fake or in-memory stand-in:** chaincode events; the Java service against an unavailable orderer/other org.
- **Not verified at all:** section A above, and everything in sections B-D marked OPEN or NOT BUILT.

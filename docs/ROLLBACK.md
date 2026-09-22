# Rollback

## F2/F3 envelope encryption + key management, written 2026-09-22 BEFORE building it

**Revert target:** git tag `phase5-ready` (commit `92df666`, pushed to origin). Everything below is
uncommitted until the owner reviews it live; roll back with `git stash -u` (keeps a copy) or
`git reset --hard phase5-ready && git clean -fd`.

**What this stage adds:** Flyway `V6__envelope_encryption.sql` (`user_keys`, `evidence_content_keys` - new
tables, no existing table altered); new `crypto/` (or similarly named) package (`ContentKeyService`,
`UserKeyService`, wrap/unwrap helpers); `EncryptionProperties` (+1 new env var,
`BLOCKEVIDENCE_ENCRYPTION_MASTER_KEY`); edits to `EvidenceService` (register/update gain
encrypt/decrypt/unwrap steps, `update` gains a new 403 `KEY_NOT_AUTHORISED` path), `CaseService`
(`addMember`/`removeMember` gain re-wrap/revoke calls), `DevUserSeeder` (provisions a keypair per seeded
user), a new `GET /api/evidence/{id}/file` endpoint + controller method. **`VerificationService` is NOT
changed** (section 7 of the design: it already hashes whatever bytes it fetches, and those bytes are now
ciphertext - no code there needs to know encryption exists).

**Non-git side effects:**
- **Postgres:** Flyway V6 creates `user_keys`, `evidence_content_keys`. Undo:
  `docker exec be-postgres psql ... -c "drop table evidence_content_keys, user_keys; delete from flyway_schema_history where version='6'"`.
- **IPFS:** every file/metadata document pinned during live verification from this point on is CIPHERTEXT
  (`IV || AES-256-GCM(...)`), not the plaintext earlier phases pinned - reading an old CID from before this
  stage still returns plaintext (unaffected; ECK-encryption only applies going forward, existing evidence is
  never retroactively re-encrypted, matching the project's "never touch existing ledger/IPFS content" stance).
- **Ledger:** `fileSha256`/`metadataSha256` recorded on the ledger for any evidence registered during/after
  this stage's live verification are ciphertext hashes, not plaintext hashes - permanent, like all ledger
  writes; a rollback of the CODE does not change what is already on the chain.
- **No new container, no new external service** - the master key is a config value only
  (`BLOCKEVIDENCE_ENCRYPTION_MASTER_KEY`), never written to disk by the app itself.

**Pre-condition checked before building:** CONSTRAINTS.md C-10 added and approved by the owner (the
master-key single-point-of-compromise trade-off is accepted going in, not discovered after).

**Re-check after rollback:** `git describe --tags` shows `phase5-ready`; `./mvnw test` reports the pre-F2/F3
count passing; `GET /api/evidence/{id}` for evidence registered before this stage still round-trips and
verifies exactly as before (plaintext, unaffected).

---

## A2 chaincode cutover (evidence v1.3), written 2026-09-22 BEFORE deploying it

**This is the risky edit CLAUDE.md asks for a rollback note before, not after.** Deploying `evidence` v1.3 makes the
chaincode REFUSE every write whose caller certificate has no `role`/`hf.EnrollmentID` attribute - which is every
identity that existed before A2, including the backend's own service identity (`User1`). By design (A2-Q3, hard
cutover), **there is no way to make v1.3 accept the old shared identity again**; the only way back is:

1. **Revert target:** commit `202ba64` (tag `phase3-done`) if this session's E3/A2 work is reset with git, OR simply
   keep pointing the backend at chaincode `evidence` v1.2 (unchanged, still committed on the channel) by NOT changing
   `blockevidence.fabric.chaincode`/deploying nothing further - v1.2's `authorise()` never reads certificate
   attributes at all, so it keeps accepting the shared identity exactly as before, with or without a wallet configured.
2. **A chaincode version cannot be un-deployed.** If v1.3 is deployed and needs to be walked back for ANY reason
   (an enrollment gap discovered late, a real user with no wallet entry), the fix is deploying v1.4 with the OLD
   (Phase 2/3) `authorise()` restored - a new higher sequence, same as every other chaincode change in this project.
3. **Pre-condition, checked and passing before v1.3 is deployed (this session):** all 6 enabled dev users are
   enrolled (`scripts/fabric/enroll_users.sh`, verified against real PostgreSQL) and a live write signed with a real
   wallet identity against the CURRENT (v1.2, non-enforcing) chaincode already succeeds and shows the correct
   creator certificate via `qscc` (TEST_CHECKLIST Appendix P3-A2) - so v1.3's enforcement is not expected to break
   any of the 6 dev users' writes.
4. **What would immediately break if a 7th user existed with no wallet entry:** every write they attempt, with
   `403 LEDGER_IDENTITY_MISSING` from the Java side before the chaincode is even asked (not a chaincode error) - not
   currently possible since users exist only through `DevUserSeeder`, all 6 of whom are enrolled.
5. **Files changed for this stage** (all additive/new, nothing in Phase 1/2 evidence code removed): chaincode
   `authorise()` (`evidence.go`), `policy.go` (attribute name consts), fake test identity (`fake_ledger_test.go`),
   `identity_test.go`; Java `ledger/IdentityStore`, `ledger/WalletIdentity`, `ledger/FileWalletIdentityStore`,
   `ledger/WalletHealthIndicator`, `FabricProperties` (+2 fields), `LedgerErrorCode` (+1 value), `FabricLedgerService`
   (writes now use a per-user `Gateway`, reads unchanged), `application.yml` (+2 properties), `pom.xml` (+1 explicit
   dependency, already transitively present); `scripts/fabric/bootstrap_registrar.sh`, `scripts/fabric/enroll_users.sh`.

**Side effects git does NOT undo, specific to this stage:**
- **Fabric CA:** `be-registrar` is registered on `ca_org1` (least-privilege, per docs/A2_IDENTITY_DESIGN.md; cannot
  be deleted, only revoked) and all 6 dev users are registered and enrolled with a `role`/`hf.EnrollmentID`
  certificate (one-time secrets already consumed, `maxenrollments 1`). None of this is undone by reverting Java or
  chaincode source.
- **Wallet directory:** created outside the repo at the operator-chosen `FABRIC_WALLET_DIR`; deleting it makes every
  write answer `LEDGER_IDENTITY_MISSING` again (safe - it is exactly the "not enrolled" state).
- **Chaincode `evidence` v1.3** (once deployed below) is committed on the channel permanently, same as every
  earlier version.

## Phase 3 (D1-D3, E1-E3, A2), written 2026-09-22 before the edit

**Revert target:** git tag `phase2-done` (commit `7b814eb`, pushed to origin). Earlier: `phase2-spring-done` (`9e159b5`), `phase1-done` (`c1a4d4b`).
Phase 3 work is uncommitted until the owner reviews it. Roll back with `git stash -u` or `git reset --hard phase2-done && git clean -fd`.

**Repository changes expected:** new Flyway migration `V2__cases.sql` (Postgres schema: a NEW migration, never an edit of V1); new
entities/services/controllers for status, custody and cases; additive chaincode functions (a NEW chaincode version, see below);
`LedgerService` gains methods (additive); docs. **Nothing that authenticates Phase 2 writes changes before the owner approves the A2 design
(docs/A2_IDENTITY_DESIGN.md).**

**Side effects git does NOT undo:**
- **Postgres:** Flyway V2 creates tables `cases`, `case_members`. Undo: `docker exec be-postgres psql ... -c "drop table case_members, cases; delete from flyway_schema_history where version='2'"`
  (or remove volume `be-postgres-data`).
- **Fabric CA (A2 spike):** the design work registers and enrolls a throwaway identity `a2spike-*` on `ca_org1` and revokes it. A CA registry entry
  cannot be deleted, only revoked. Harmless, and listed here so it is not a surprise.
- **Fabric ledger / chaincode:** any new chaincode version (`evidence` v1.2+ with a higher sequence) is committed to the channel and cannot be
  removed; see docs/FABRIC_RUNBOOK.md. Ledger test data is permanent; Phase 3 test records use case ids `FAB-P3-*`.
- Containers started for verification are stopped again at the end of the session.

**Recorded after the fact (2026-09-22, end of Phase 3 build):**
- Chaincode `evidence` **v1.2 seq 3** is committed on the channel (`querycommitted` shows `evidence 1.2 seq 3`); it cannot be removed, only superseded.
  Records written under it carry the `transfer` field, which v1.1 code would not understand: to go back you would deploy v1.1's code as a
  new higher sequence, not "undo" seq 3.
- An unused package `evidence_1.0:a06b4dc1...` is installed on BOTH peers (a first deploy was run without `VER=1.2 SEQ=3` and refused at approval;
  nothing was committed). It is harmless and was left in place.
- The A2 CA spike registered UUID-named test identities on `ca_org1`; all were revoked afterwards (registry entries remain by design).
  The original identities (`admin`, `peer0`, `user1`, `org1admin`, `appUser`) were verified untouched. The spike scripts were not kept in the repository.
- Postgres has tables `cases`, `case_members` and Flyway row `2 | cases` (undo commands above). Two test cases `FAB-P3-CASE-*` exist.
- New ledger records with case ids `FAB-WIRE-P3`, `FAB-P3-1`, `FAB-P3-CASE-*` are permanent.

---

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

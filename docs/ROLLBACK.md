# Rollback

## Phase 2 build (evidence management: B1-B5, C1-C3, G2), written 2026-09-23 before the edit

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

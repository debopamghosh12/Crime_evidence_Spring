# Rollback

## Phase 1 build (project generation + all Phase 1 code), written 2026-09-21 before the edit

**Revert target:** git tag `baseline-docs` (commit `66c8932`, docs-only, made before any code existed:
`CLAUDE.md` and `docs/`). **Phase 1 itself is not committed yet**, so until it is, this tag is the only
revert point and everything after it exists only in the working tree. Commit Phase 1 (and tag it) to get
a second revert point.

**What Phase 1 added** (delete these to return to code-free baseline):
`pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `.gitignore`, `.gitattributes`, `src/`, `target/`.
New docs: `docs/features/*.md` (7 files), `docs/bugs/jvm-timezone-postgres.md`.

**What it modified** (restore with `git checkout baseline-docs -- <path>`): `docs/ARCHITECTURE.md`,
`docs/DECISIONS.md`, `docs/FLOW.md`, `docs/TEST_CHECKLIST.md`, `docs/HANDOVER.md`, `docs/ROLLBACK.md`.
`CLAUDE.md`, `docs/CONSTRAINTS.md` and `docs/FEATURE_LIST.md` were not changed after baseline.

**How to roll back**
1. Code only, keep the doc trail: review with `git clean -ndx -e docs -e CLAUDE.md -e .idea`, then run it
   without `-n`. (`.idea/` is IntelliJ's and is untracked.)
2. Everything including docs: copy any docs you want to keep somewhere safe, then
   `git reset --hard baseline-docs && git clean -fd`. This discards the post-baseline doc edits.
3. One file: `git checkout baseline-docs -- <path>`.

**Non-git side effects to undo**
- Docker: containers `be-postgres` (host port 5433) and `be-ipfs` (5001) and volume `be-postgres-data`:
  `docker rm -f be-postgres be-ipfs` and `docker volume rm be-postgres-data`. They are currently stopped.
  Pre-existing containers (the Fabric prototype's peers, orderer, CAs, and others) were never touched.
- The Maven wrapper downloaded Maven 3.9.16 into `~/.m2/wrapper` and dependencies into `~/.m2/repository`;
  harmless, safe to leave.
- Throwaway credentials for live runs live in the session scratchpad, outside the repository.

**Re-check after a rollback**
- `git status` is clean and `git describe --tags` shows `baseline-docs`.
- `docs/` still contains the seven skeleton files plus `bugs/` and `features/`.
- In IntelliJ: File -> Invalidate Caches, and remove the stale Maven project if `pom.xml` is gone.

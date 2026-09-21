# Rollback

## Phase 1 build (project generation + all Phase 1 code) — written 2026-09-21, before the edit

**Revert target:** git tag `baseline-docs` (docs-only commit made before any code existed: `CLAUDE.md`
and `docs/`).

**What the edit adds** (everything below can be deleted to return to baseline):
`pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `HELP.md`, root `.gitignore`, `src/`, `target/`.

**What it modifies** (restore from the tag if needed): `docs/*.md` (HANDOVER, DECISIONS, FLOW,
ARCHITECTURE, TEST_CHECKLIST, CONSTRAINTS).

**How to roll back**
1. Code only, keep the doc trail: `git rm -r --cached` is not needed; just delete the generated paths
   listed above (`git clean -fdx -e docs -e CLAUDE.md -e .idea` after reviewing `git clean -ndx`).
2. Everything including docs: `git reset --hard baseline-docs` then `git clean -fd`. This discards
   post-baseline docs edits (HANDOVER, DECISIONS, bug/feature write-ups), so copy them out first.
3. One file: `git checkout baseline-docs -- <path>`.

**Non-git side effects to undo:** verification containers, if created: `docker rm -f be-postgres be-ipfs`
and `docker volume rm be-postgres-data`.

**Re-check after a rollback**
- `git status` is clean and `git describe --tags` shows `baseline-docs`.
- `docs/` still contains all seven skeleton files plus `bugs/` and `features/`.
- In IntelliJ: File → Invalidate Caches, and remove the stale Maven import if `pom.xml` is gone.

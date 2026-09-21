# BlockEvidence Backend — Project Rules

Spring Boot rewrite of a Hyperledger Fabric evidence-management backend
(final-year project). IDE: IntelliJ IDEA. Full feature list, priorities
and build phases are in `docs/FEATURE_LIST.md`.

## Mandatory files — read these before doing anything

At the start of EVERY session, before writing or changing any code, read
in this order:
1. `docs/HANDOVER.md` — current state: what's done, in progress, broken, to avoid
2. `docs/ARCHITECTURE.md` — system shape: modules, services, data flow
3. `docs/CONSTRAINTS.md` — hard rules you must not break
4. `docs/FLOW.md` — how execution actually travels through the code

If any of these don't exist yet, create them as empty skeletons (headings
only) before starting work, and say so.

## Session start

State in one line: which reading you took of the current task, what
you're about to touch, and which phase/feature ID from FEATURE_LIST.md
this maps to.

## While working

- **DECISIONS.md** (`docs/DECISIONS.md`): every time you choose a library,
  pattern, or tradeoff where a reasonable alternative existed, append an
  entry: what you chose, what you rejected, why. One paragraph max. Do
  this as you go, not retroactively.
- **Explicit comments**: comment non-obvious logic as you write it —
  not what the line does, but why it exists: what calls into this block,
  what it assumes is already true, what breaks if that assumption is
  wrong. Skip comments on self-explanatory code.
- **FLOW.md** (`docs/FLOW.md`): whenever you add or change a call path
  (a new endpoint, a new service call chain, a new chaincode
  interaction), update the relevant section — what calls what, in what
  order. Mark which part of the path is currently being modified.
- **CONSTRAINTS.md** (`docs/CONSTRAINTS.md`): never violate an entry
  here. If a task seems to require it, stop and ask instead of working
  around it silently.

## Bugs and features

For every bug fixed or feature built, create one file:
`docs/bugs/<short-name>.md` or `docs/features/<short-name>.md`. Trace it
start to finish: how it was found/scoped, what was tried, what worked,
what didn't, how it was verified (command + actual output, not "tested
and it works").

## Before calling anything "done"

Run through `docs/TEST_CHECKLIST.md`. If a check doesn't exist yet for
what you just built, add it to the checklist first, then run it. Never
mark a task complete on a vibe check — paste the actual command and
actual output.

## Before any large or risky edit

Write or update `docs/ROLLBACK.md` first: which commit/tag to revert to,
which files would need restoring, what to re-check after a rollback.
Do this before the edit, not after something breaks.

## End of every session

Append a 5-line entry to `docs/HANDOVER.md` (top of file, most recent
first): what we did, what's left, what's broken, what to watch out for,
one line max each. This is what the next session reads first — keep it
current, not a full dump.

## Hard constraints (also see docs/CONSTRAINTS.md for the growing list)

- No new dependency without asking first and logging it in DECISIONS.md
- Never touch a module outside the current phase's scope without flagging it
- Ledger calls only through the `LedgerService` interface — never call
  Fabric directly from a controller or other service
- Don't delete evidence records — archive/dispose only (see FEATURE_LIST.md B5)

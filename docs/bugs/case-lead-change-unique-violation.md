# Bug: changing a case's lead officer answers 500 (unique-constraint violation)

**Found:** 2026-09-22, first live run of Phase 3 (`scripts/live/live_phase3.sh`) against real PostgreSQL 16.
**Fixed:** same day. **Feature affected:** E1/E2 (`PUT /api/cases/{id}` with a new `leadOfficerId`).
**Why the unit tests did not catch it:** they use mocked repositories; only a real database has the constraint and the flush order.

## Symptom

```
change the lead officer to the prosecutor + rename:    500
```
Application log (`GlobalExceptionHandler`, "Unhandled exception on PUT /api/cases/885eea7f-..."):
```
org.springframework.dao.DataIntegrityViolationException: could not execute statement [ERROR: duplicate key value violates unique constraint "uq_case_members"
  Detail: Key (case_id, user_id)=(885eea7f-e3f4-4f81-938e-431849ebcbf8, f47ff616-80f2-4b54-b128-3b1b3fb6b8d0) already exists.]
  [insert into case_members (added_at,added_by,case_id,case_role,user_id,id) values (?,?,?,?,?,?)]
```
The client saw the generic 500 error body (no internals leaked, K1 worked as designed), but the operation failed and the case was unchanged.

## Diagnosis

1. The failing SQL is an `insert` into `case_members` for the **previous lead officer's** (case, user) pair, which already exists.
2. `CaseService.update` handled a lead change as "delete the old lead's membership row, then insert a new one with role INVESTIGATOR".
   Hibernate does not run SQL in the order the code calls it: at flush time it executes **inserts before deletes**. So the insert of
   the (case, user) pair ran while the row it was meant to replace still existed, and `UNIQUE (case_id, user_id)` refused it.
3. The mocked `CaseServiceTest` could not show this: a mock has no constraint and no flush ordering.

## What was considered

| Option | Verdict |
|---|---|
| Call `members.flush()` between the delete and the insert | Works, but keeps a delete where none is needed, and depends on remembering to flush. |
| **Change the `case_role` of the existing row in place** (`CaseMember.changeRole`) | **Chosen.** One `UPDATE`, no ordering problem, no delete at all, and the row keeps its original `added_by/added_at`. |
| Drop the unique constraint | Rejected: it is what stops a user being on a team twice (proved live: `ALREADY_A_MEMBER`). |

## Fix

- `CaseMember.changeRole(CaseRole)`; `CaseService.update` now demotes the current lead's row to INVESTIGATOR and either promotes the new
  lead's existing row to LEAD_OFFICER or inserts a new row. Removing a *member* (`DELETE .../members/{userId}`) is unchanged and remains
  the only delete in the code.
- Regression tests in `CaseServiceTest`: the lead-change tests now assert `members.delete(...)` is **never** called, and a new test
  covers promoting an existing member. The test fake was also corrected so that saving an already-managed row does not add a second
  one (like JPA).

## Verification (real PostgreSQL, real Fabric running alongside; TEST_CHECKLIST P3-L)

Before the fix: `change the lead officer to the prosecutor + rename: 500`. After (same script, rerun):
```
   change the lead officer to the prosecutor + rename:    200
   title: Burglary at 12 High St (renamed) | lead: 286529a7.. | team: [('286529a7..', 'LEAD_OFFICER'), ('b9481c66..', 'FORENSIC_ANALYST'), ('f47ff616..', 'INVESTIGATOR')]
```
The prosecutor was already a PROSECUTOR member of that case, so this run also exercises "promote an existing member". `grep -c ERROR` on the
application log after the rerun: 0.

## Lesson

Anything that depends on a database constraint or flush order needs a live check against the real database; mocked repositories prove
the logic, not the persistence. There is still no automated real-database test (Testcontainers, `docs/KNOWN_GAPS.md` section C).

# E1 and E2: Cases, and officers assigned to them

**FEATURE_LIST:** E1, E2. **Phase:** 3. **Status:** done, verified live against real PostgreSQL (Flyway `V2__cases.sql`).
**E3 (linking evidence to cases) is NOT built: it touches Phase 2 code and is waiting for the owner's choice, see below.**

## What was built (entirely off-chain, C-06: the ledger only ever sees the case *number* as a string)
Tables `cases` and `case_members` (a NEW migration; V1 untouched). Case number: unique case-insensitively, same character set as a ledger
`caseId`. Status OPEN/CLOSED (no endpoint closes a case yet).

| Endpoint | Roles | Notes |
|---|---|---|
| `POST /api/cases` `{caseNumber, title, description, leadOfficerId}` | ADMIN, PROSECUTOR | creator = token user; lead officer becomes member `LEAD_OFFICER` |
| `GET /api/cases`, `GET /api/cases/{id}` | any authenticated | includes the team |
| `PUT /api/cases/{id}` `{title?, description?, leadOfficerId?}` | ADMIN, PROSECUTOR | number and status cannot change; at least one field required |
| `POST /api/cases/{id}/members` `{userId, caseRole}` | ADMIN, PROSECUTOR | case roles: INVESTIGATOR, FORENSIC_ANALYST, PROSECUTOR, OBSERVER (LEAD_OFFICER only via `leadOfficerId`) |
| `DELETE /api/cases/{id}/members/{userId}` | ADMIN, PROSECUTOR | the lead officer cannot be removed |

Lead officer must be COLLECTOR, FORENSIC_ANALYST or PROSECUTOR and enabled. **There is no way to delete a case** (405). Changing the lead
demotes the previous lead to INVESTIGATOR in place (see docs/bugs/case-lead-change-unique-violation.md).
Removing a membership row is the only `delete` in the code base; it is not evidence, so C-02 does not apply (a test enforces that no
evidence-deleting call exists).

## Verification (TEST_CHECKLIST P3-L, E1/E2 sections)
Create 201 (creator from the token, lead is a member) -> same number in other letter case 409 `CASE_NUMBER_TAKEN` -> AUDITOR lead 400
`INVALID_LEAD_OFFICER` -> COLLECTOR creating 403 -> bad number 400 with field errors -> anonymous 401. Members: add 200, duplicate 409
`ALREADY_A_MEMBER`, LEAD_OFFICER via members 400, COLLECTOR adding 403, remove 200, remove lead 400 `CANNOT_REMOVE_LEAD_OFFICER`, remove
non-member 404, change lead + rename 200 (after the bug fix), empty update 400. `psql` output confirms the rows and Flyway history
`1 users and refresh tokens | 2 cases`.

## E3: evidence <-> case linking, decision needed
Today `caseId` on evidence is a free string; nothing checks that it names a real case (live: registering with the new case's number
works, but so would any other string). To link properly, one of these must change Phase 2 code, so it needs the owner's choice:

| | A. Chaincode index | B. Spring link table |
|---|---|---|
| How | chaincode `CASE~caseId~evidenceId` composite keys + `FindByCase`; changes `CreateEvidence` | table `case_evidence`; changes `EvidenceService.register` |
| Touches Phase 2 | chaincode + deployed v1.3 | B1 register path |
| Truth lives on | the ledger (tamper-evident) | PostgreSQL (mutable) |
| Extra | case summary can list evidence straight from the ledger | list needs a ledger call per item |

Either way, an off-by-default flag `blockevidence.cases.require-existing` would make register reject an unknown case number, and the
case summary would list its evidence. Not built until chosen.

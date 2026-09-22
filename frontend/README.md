# BlockEvidence Frontend

This is the UI layer only. It came from a separate repository
(`https://github.com/debopamghosh12/Crime_evidence`, its `client/` directory) - kept for its pages,
components and styling, with every data call rewired to call this project's real Spring Boot backend
instead. That source repo's own backend (`api/`, Node/Express) and database schema (`prisma/`) were
**never copied, never referenced, and are never run here.** See `docs/DECISIONS.md` D-069 for the full
scoping rationale.

## Running it

```bash
cp .env.local.example .env.local   # once, if .env.local doesn't already exist
npm install
npm run dev                        # http://localhost:3000
```

Needs the real backend running on `http://localhost:8080` (see the project root's own README /
`docs/TEST_CHECKLIST.md` for how to start it) and `SecurityConfig`'s CORS allow-list to include
`http://localhost:3000` (the default - see `application.yml`, `blockevidence.cors.allowed-origins`).

## Scope: what's kept, what's cut, what's added

Full owner-directed decision, logged here per the project's usual honesty pattern (CLAUDE.md).

### Kept and rewired to the real backend (Part A)
Every data call was pointed at this project's actual controllers, with the request/response shapes
corrected to match (they never matched verbatim - see the mapping report from the session that scoped
this work). In particular:
- **Login** is a real two-step flow: `POST /api/auth/login` (returns tokens only) then
  `GET /api/auth/me` (returns the profile) - the source repo's backend returned both in one call, ours
  doesn't.
- **Custody transfer** is the real two-step flow (initiate, then a separate accept/reject by the
  receiver, via a pending-transfers view) - never faked as the source repo's single-call transfer.
- **Verify** points at the real authenticated by-evidence-ID endpoint
  (`GET /api/evidence/{id}/verify`), not a new public hash-lookup endpoint (the source repo's
  `/verify/:hash` has no backend equivalent here and was not rebuilt).
- Evidence register/view/search, cases (list/create/view/update), dashboard/stats, notifications
  (list/mark-read), activity feed, and the chain-of-custody PDF report (download button -> the real
  `/report` endpoint) are all wired the same way: real path, real shape, real auth header.

### Cut entirely - no backend exists, removed rather than left as dead clicks (Part B)
- **Self-registration/signup.** There is no user-registration endpoint in this backend at all (A4,
  admin user management, was never built - see `docs/KNOWN_GAPS.md`). The login page now says
  "Accounts are provisioned by an administrator"; the sign-up link and page are gone.
- **QR code generation/display** (would-be I2, never built).
- **Comments/discussion threads** on evidence (nothing like this exists in FEATURE_LIST.md).
- **Lab results** panel (same - no backend concept).
- **Generic access-request flow** (distinct from, and not to be confused with, the real disposal
  request/approve flow added below).
- **"CrimeBox"** (a shared-keypair, join-by-invite-key mechanism for accessing a case's evidence) -
  removed entirely, including the onboarding step on the dashboard that assumed it exists. Nothing in
  this backend's case/access model resembles it.

### Added - backend features that had no frontend before (Part C)
These existed only in Swagger/Postman before this integration; minimal, functional UI was added so
they're reachable:
- **Disposal**: a "Request disposal" action (reason required) on evidence detail, and an
  Approve/Reject view for JUDGE (the only role `Permissions.DECIDE_DISPOSAL` allows).
- **Version history**: past versions and who changed what, from evidence detail
  (`GET /api/evidence/{id}/history`).
- **Case officer assignment**: add/remove officers from case detail
  (`POST`/`DELETE /api/cases/{id}/members...`).
- **Audit log**: a simple table for ADMIN, `GET /api/audit`, including
  `TOKEN_USED_AFTER_DEACTIVATION` entries.
- **Encrypted file download**: a distinct "Download file" action on evidence detail
  (`GET /api/evidence/{id}/file`, F2/F3's decrypt endpoint) - separate from viewing metadata, and
  subject to the same real 403/`KEY_NOT_AUTHORISED` authorization already verified live when F2/F3 was
  built.

### Role mapping
The source repo's role names never matched this backend's `Role` enum. Mapping used throughout:

| Source repo role | Backend `Role` |
|---|---|
| `officer` | `COLLECTOR` |
| `head_officer` | `FORENSIC_ANALYST` |
| `lawyer` | `PROSECUTOR` |
| `judge` | `JUDGE` |

`AUDITOR` and `ADMIN` have no dedicated onboarding role name (there is no self-registration at all -
see Part B) and get no frontend entry point beyond what the Part C additions above need (the audit log
view and disposal-approval view respectively).

## Known gap between the mapping report and the real backend

The mapping exercise that scoped this work found that **every single endpoint** needed a frontend-side
change (no path or response shape matched verbatim - different prefix, different field names,
different nesting). That work is being done incrementally, in the order the owner specified: auth,
then evidence register/view, then custody transfer, then cases, then dashboard, then
notifications/activity, then verify, then the report - each verified live against the real backend
before moving to the next. Check `docs/DECISIONS.md` (D-069 onward) and this project's `HANDOVER.md`
for how far that has actually gotten; do not assume everything above is finished just because it's
listed here as in-scope.

# BlockEvidence Frontend

This is the UI layer only. It came from a separate repository
(`https://github.com/debopamghosh12/Crime_evidence`, its `client/` directory) - kept for its pages,
components and styling, with every data call rewired to call this project's real Spring Boot backend
instead. That source repo's own backend (`api/`, Node/Express) and database schema (`prisma/`) were
**never copied, never referenced, and are never run here.** See `docs/DECISIONS.md` D-069 onward for
the full scoping rationale and every decision made while wiring it.

**Status: finished.** Every item below is wired, live-verified against the real backend (real Fabric,
not just `memory-ledger` - see D-071), and committed. This is not a snapshot of work-in-progress.

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

### Kept and rewired to the real backend (Part A - done)
Every data call was pointed at this project's actual controllers, with the request/response shapes
corrected to match (they never matched verbatim - see D-069's mapping report). In particular:
- **Login** is a real two-step flow: `POST /api/auth/login` (returns tokens only) then
  `GET /api/auth/me` (returns the profile) - the source repo's backend returned both in one call, ours
  doesn't. **Sign Out also now calls the real `POST /api/auth/logout`** (D-081) - a gap found in the
  final pass: the button worked, but never actually revoked the refresh token server-side until fixed.
- **Evidence register** sends the real multipart shape (`RegisterEvidenceRequest` as a `"metadata"`
  JSON part + a single `"file"` part), not the source repo's flat fields.
- **Custody transfer** is the real two-step flow (initiate, then a separate accept/reject by the
  receiver, via a pending-transfers view) - never faked as the source repo's single-call transfer.
- **Verify** points at the real authenticated by-evidence-ID endpoint
  (`GET /api/evidence/{id}/verify`), not a new public hash-lookup endpoint (the source repo's
  `/verify/:hash` has no backend equivalent here and was not rebuilt - see Part B).
- Evidence view/search, cases (list/create/view/update), dashboard/stats, notifications
  (list/mark-read), activity feed, and the chain-of-custody PDF report (`GET /api/evidence/{id}/report`)
  are all wired the same way: real path, real shape, real auth header.

### Cut entirely - no backend exists, removed rather than left as dead clicks (Part B - done)
- **Self-registration/signup.** There is no user-registration endpoint in this backend at all (A4,
  admin user management, was never built - see `docs/KNOWN_GAPS.md`). The login page says "Accounts
  are provisioned by an administrator"; the sign-up link, the `/register` page itself, and the public
  landing page's two "register" CTAs are all gone (D-080 - a page with no incoming link is still a real,
  directly-navigable dead page until the file itself is deleted).
- **A public by-hash verify page** (`/verify/[hash]`) - no such lookup endpoint exists (D-075).
- **QR code generation/display** (would-be I2, never built).
- **Comments/discussion threads** on evidence (nothing like this exists in FEATURE_LIST.md).
- **Lab results** panel (same - no backend concept).
- **Generic access-request flow** (distinct from, and not to be confused with, the real disposal
  request/approve flow added below).
- **"CrimeBox"** (a shared-keypair, join-by-invite-key mechanism for accessing a case's evidence) -
  removed entirely (context, components, provider) including the dashboard onboarding step that
  assumed it exists (D-073). Nothing in this backend's case/access model resembles it.

### Added - backend features that had no frontend before (Part C - done)
These existed only in Swagger/Postman before this integration; minimal, functional UI was added so
they're reachable, each verified live including the authorization boundary, not just the happy path:
- **Disposal**: a "Request disposal" action (COLLECTOR/PROSECUTOR, reason required) on evidence detail,
  and an Approve/Reject view shown to every role - a non-JUDGE gets a real 403 back from the backend,
  confirmed live in both directions (D-077).
- **Version history**: every real version, actor, reason and ledger transaction id, from
  `GET /api/evidence/{id}/history` (D-077).
- **Case officer assignment**: add/remove officers from case detail
  (`POST`/`DELETE /api/cases/{id}/members...`), re-verified live end to end (D-078).
- **Audit log**: `GET /api/audit` for ADMIN/AUDITOR only, shown to every role (a non-ADMIN/AUDITOR gets
  a real 403); the live verification run even captured its own 403 test as a real `ACCESS_DENIED` row
  (D-079).
- **Encrypted file download**: a distinct "Download file" action on evidence detail
  (`GET /api/evidence/{id}/file`, F2/F3's decrypt endpoint) - separate from viewing metadata, subject to
  the same real 403 `KEY_NOT_AUTHORISED` boundary already verified live when F2/F3 was built.

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

## Button → endpoint table (final pass)

Every actionable control in the app, real backend endpoint it calls, and where it was verified live.
Purely presentational controls (modal Cancel/close, nav links, table sort/expand with no network call)
are omitted - they have no endpoint to dead-click against. `D-0xx` references are `docs/DECISIONS.md`.

| Page | Button / action | Endpoint | Verified |
|---|---|---|---|
| Login | Sign In | `POST /api/auth/login` → `GET /api/auth/me` | D-069 |
| Any (sidebar) | Sign Out | `POST /api/auth/logout` | D-081 |
| Dashboard | (auto-loads) | `GET /api/dashboard`, `GET /api/activity?size=5` | D-073 |
| Analytics | (auto-loads charts) | `GET /api/dashboard`, `GET /api/cases` (for the count) | D-079 |
| Evidence (list) | Log New Item → | navigates to `/evidence/new` | - |
| Evidence (list) | search box | `GET /api/evidence/search` | D-070 |
| Evidence (list) | row → | navigates to `/evidence/{id}` | - |
| Evidence (list) | Prev / Next | `GET /api/evidence/search` (paged) | D-070 |
| Register Evidence | Register Evidence | `POST /api/evidence` (multipart) | D-070, D-071 |
| Evidence detail | Request Transfer → Confirm Transfer | `POST /api/evidence/{id}/transfers` | D-070, D-071 |
| Evidence detail | Request Disposal → Submit Request | `POST /api/evidence/{id}/disposal` | D-077 |
| Evidence detail | Approve (JUDGE only) | `POST /api/evidence/{id}/disposal/approve` | D-077 (incl. 403 for non-JUDGE) |
| Evidence detail | Reject (JUDGE only) | `POST /api/evidence/{id}/disposal/reject` | D-077 |
| Evidence detail | Download File (×2 locations) | `GET /api/evidence/{id}/file` (F2/F3 decrypt) | D-070 |
| Evidence detail | Verify Integrity | `GET /api/evidence/{id}/verify` | D-075 |
| Evidence detail | Generate Report | `GET /api/evidence/{id}/report` | D-076 |
| Evidence detail | (auto-loads) | `GET /api/evidence/{id}`, `GET /api/evidence/{id}/history` | D-070, D-077 |
| Chain of Custody | Accept | `POST /api/evidence/{id}/transfers/accept` | D-070, D-071 |
| Chain of Custody | Reject → Confirm Reject | `POST /api/evidence/{id}/transfers/reject` | D-070, D-071 |
| Chain of Custody | (auto-loads) | `GET /api/transfers/pending` | D-070 |
| Cases (list) | New Case → Create | `POST /api/cases` | D-072 |
| Cases (list) | (auto-loads) | `GET /api/cases` | D-072 |
| Cases (list) | row → | navigates to `/cases/{id}` | - |
| Case detail | Edit → Save | `PUT /api/cases/{id}` | D-072 |
| Case detail | Add (member) | `POST /api/cases/{id}/members` | D-078 |
| Case detail | Remove (member icon) | `DELETE /api/cases/{id}/members/{userId}` | D-078 |
| Case detail | evidence row → | navigates to `/evidence/{id}` | D-072 |
| Case detail | (auto-loads) | `GET /api/cases/{id}` | D-072 |
| Activity Feed | (auto-loads), Refresh | `GET /api/activity` | D-074 |
| Activity Feed | Prev / Next | `GET /api/activity` (paged) | D-074 |
| Activity Feed | row → | navigates to `/evidence/{id}` | D-074 |
| Notifications | (auto-loads) | `GET /api/notifications` | D-074 |
| Notifications | click an unread row | `POST /api/notifications/{id}/read` | D-074 |
| Notifications | Mark all read | `POST /api/notifications/{id}/read` × N (no bulk endpoint exists) | D-074 |
| Audit Log | (auto-loads), Refresh | `GET /api/audit` (ADMIN/AUDITOR only; real 403 otherwise) | D-079 |
| Audit Log | Prev / Next | `GET /api/audit` (paged) | D-079 |

## Known, accepted limits (not gaps left by oversight)

- **No user-lookup endpoint exists** (A4 was never built), so every "who" field in the UI - custody
  transfer recipients, case lead officers, case-member ids - is a raw UUID the caller must already know,
  not a searchable picker. Every occurrence is commented in the source as `// no user-lookup endpoint`.
- **No bulk endpoints** for marking all notifications read, or for "my outgoing custody transfers" -
  the backend simply doesn't expose them. The former is worked around with N real calls; the latter has
  no workaround and is stated plainly on the Chain of Custody page instead of being faked.
- **`memory-ledger` has no persistence** (a backend restart under that profile loses evidence data,
  Postgres-native data survives) - a backend/environment limit, not a frontend one; see D-071 for how
  this frontend's own live verification switched to a real, persistent Fabric network instead.

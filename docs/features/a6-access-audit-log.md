# A6: Access audit log

**FEATURE_LIST:** A6 (P1). **Phase:** 4. **Status:** done, verified live end to end - including the specific
deactivation-visibility scenario the owner required before this could be marked done, not just the schema.

## Scoping
"Log every view, download and failed attempt with user, resource, time and IP." Paired explicitly by the owner
with the Phase 1 access-token-lag limitation (an access token stays valid up to 15 minutes after a user is
deactivated): A6 must make any use of that window VISIBLE, without fixing the underlying TTL gap itself.

## What was built
- `audit_log` (Flyway V5): `user_id` (nullable - a failed attempt may have no verifiable principal at all),
  `email`, `action`, `resource`, `ip_address`, `detail`, `occurred_at`.
- **No AOP.** Instrumented directly at 4 points, since only 2 controller methods need VIEW/DOWNLOAD and the
  codebase has no existing AOP pattern to extend (DECISIONS D-056):
  - `EvidenceController.get`/`verify` -> `AuditService.recordAccess(id, download)`. `verify=true` (or the
    dedicated `/verify` endpoint, which always re-hashes) is DOWNLOAD; a plain GET is VIEW.
  - `ApiAuthenticationEntryPoint` (401, no/bad/expired token) and `GlobalExceptionHandler.handleAuthenticationFailed`
    (401, bad login credentials) -> `AUTH_FAILED`.
  - `ApiAccessDeniedHandler` and `GlobalExceptionHandler.handleAccessDenied` (403, role/permission refusal) ->
    `ACCESS_DENIED`.
- **The Phase 1 pairing:** `AuditService.recordAccess` looks up the ACTING user's CURRENT `enabled` value (a
  fresh `UserRepository` read, never trusting the JWT's claims, which say nothing about deactivation) every time
  it would log a VIEW/DOWNLOAD, and records `TOKEN_USED_AFTER_DEACTIVATION` instead when the account has since
  been disabled. The request's own behaviour is completely unchanged (still 200 - the TTL gap is not fixed here);
  only what gets logged differs.
- `GET /api/audit?userId=&action=&from=&to=&page=&size=` - `Permissions.READ_AUDIT_LOG` (ADMIN, AUDITOR only:
  narrower than ordinary evidence reads, since the log itself names users and IP addresses).

## Verification: the specific check the owner required (TEST_CHECKLIST Appendix P4-A6)
Deactivated a real user (the auditor account) DIRECTLY IN POSTGRES while their already-issued access token was
still cryptographically valid (no A4/admin-user-management endpoint exists yet to do this through the API - the
same SQL update A4 would eventually run), then used that token for a real request:
```
GET /api/evidence/{id} with the auditor's token, BEFORE deactivation:
   200; audit row: action=VIEW        email=auditor@blockevidence.local

(deactivate the auditor account directly in PostgreSQL)

GET /api/evidence/{id} with the SAME still-valid token, AFTER deactivation:
   200 (the request still succeeds - the TTL gap is not being fixed)
   audit row: action=TOKEN_USED_AFTER_DEACTIVATION   email=auditor@blockevidence.local   resource={id}

(reactivate the account; a fresh login + request afterwards)
   audit row: action=VIEW   (ordinary again)
```
The `TOKEN_USED_AFTER_DEACTIVATION` row is clearly distinguishable, in the same table and the same query, from
the ordinary `VIEW`/`DOWNLOAD` rows the SAME user's earlier requests produced - proven in the real running system,
not asserted from the schema alone. `GET /api/audit?action=TOKEN_USED_AFTER_DEACTIVATION` (as ADMIN) surfaces
exactly this one row; the same call as COLLECTOR is refused (403), confirming the narrower read permission.

Also verified live: an ordinary VIEW and DOWNLOAD by the auditor role; a 403 (COLLECTOR tries a JUDGE-only
action) recorded as ACCESS_DENIED with the acting user's email; a bad-password login and a garbage bearer token
both recorded as AUTH_FAILED, the login one carrying the acting attempted email even though authentication never
succeeded (from the request, not a token that does not exist).

Automated: `AuditServiceTest` (5: a still-enabled user gets ordinary VIEW/DOWNLOAD entries, a deactivated user's
access is recorded as TOKEN_USED_AFTER_DEACTIVATION, a user deleted outright is treated the same as deactivated,
`recordAccess` with no authenticated principal does nothing, `recordDenied` carries whichever principal is
present or none at all).

## Known limits
- IP address is `HttpServletRequest.getRemoteAddr()`; no `X-Forwarded-For` handling, so behind a reverse proxy
  every entry would show the proxy's address, not the real client's. Not built (this project runs without one).
- VIEW/DOWNLOAD auditing covers evidence access only (`GET /api/evidence/{id}` and its verify variants), not
  every read endpoint (search/dashboard/activity/cases) - scoped to the resource A6's own wording names
  ("view, download"), not generic API-call logging.
- No retention/purge policy for `audit_log`; it grows without bound, same class of gap as `refresh_tokens` (ARCHITECTURE section 10).

# K1: Validation and one global error format

**FEATURE_LIST:** K1 (P0, new). **Phase:** 1. **Status:** done, verified 2026-09-22.

## Scoping
Every non-2xx response must have the same body so clients handle errors in one place. Bean Validation
on request DTOs feeds it. Two complications found while scoping:
- Errors come from two places that never meet: exceptions inside Spring MVC (handled by
  `@RestControllerAdvice`) and 401/403 raised in the security **filter chain**, before DispatcherServlet,
  where the advice cannot reach. Both had to be built or the format would be inconsistent exactly where
  clients most often see errors (auth).
- Spring's default for MVC-internal errors is `ProblemDetail`, a different format.

## What was built
- `exception/ApiError` (record): `timestamp, status, error, message, path, fieldErrors[]`.
- `exception/GlobalExceptionHandler extends ResponseEntityExceptionHandler`: overrides
  `handleMethodArgumentNotValid` (field errors) and `handleExceptionInternal` (every framework MVC
  exception: malformed JSON, 404, 405, 415 ...), plus explicit handlers for `AuthenticationFailedException`
  (401), `AccessDeniedException` (403), `AuthenticationException` (401), `FeatureNotImplementedException`
  (501) and a catch-all (500).
- `exception/ApiErrorWriter` + `security/ApiAuthenticationEntryPoint` + `security/ApiAccessDeniedHandler`:
  the same body for filter-level 401/403.
- Machine codes: ours are `VALIDATION_FAILED, UNAUTHENTICATED, ACCESS_DENIED, NOT_IMPLEMENTED,
  INTERNAL_ERROR`; framework errors use the HTTP status name (`BAD_REQUEST, NOT_FOUND, METHOD_NOT_ALLOWED`).
  `LEDGER_UNAVAILABLE` is reserved for Phase 2.

## Decisions and things that would have gone wrong
- **`AccessDeniedException` needs its own handler.** `@PreAuthorize` throws it from inside MVC; without a
  specific handler the catch-all would return 500 for a legitimate 403. Covered by test
  `adminRouteIsForbiddenToACollector`.
- **Messages are fixed text, never `ex.getMessage()`.** A JSON parse failure's message quotes Jackson
  internals; the 500 handler must not expose a JDBC URL or stack trace. Both are asserted in tests.
- `fieldErrors` is always present (empty list) so clients need no null check.
- Rejected: hand-listing `@ExceptionHandler`s only (D-011).

## Verification
Automated: `SecurityAndErrorFormatTest`, 17 tests, all passed (401, 403, 400 validation, 400 malformed,
404, 405, 500 no-leak, 501). Live (real server) results are in `docs/TEST_CHECKLIST.md` section 3, e.g.:

```
POST /api/auth/login {"email":"not-an-email","password":""}
HTTP 400 {"timestamp":"2026-09-21T18:31:49.371665200Z","status":400,"error":"VALIDATION_FAILED","message":"Request validation failed","path":"/api/auth/login","fieldErrors":[{"field":"email","message":"must be a well-formed email address"},{"field":"password","message":"must not be blank"}]}

GET /api/auth/me            (no token)
HTTP 401 {"timestamp":"...","status":401,"error":"UNAUTHENTICATED","message":"Authentication required","path":"/api/auth/me","fieldErrors":[]}
```

## Known limits
No `correlationId` yet (K6, P2). **Not tested:** method-level validation (`@Validated` on `@RequestParam`
or on service beans). No Phase 1 endpoint uses it. `ConstraintViolationException` from a `@Validated`
service is not handled explicitly and would presumably reach the 500 catch-all; add a handler with the
first feature that needs it.

# A1: Login with JWT (user entity, access token, rotating refresh token)

**FEATURE_LIST:** A1 (P0, changed from prototype). **Phase:** 1. **Status:** done, verified 2026-09-22.

## Scoping
"Email/password login that issues a short-lived access token plus a refresh token. Passwords stored with
BCrypt." Not in scope: creating users (A4) or the per-user Fabric identity (A2). Because A4 is unscheduled
but login needs users, a dev-profile-only seeder was approved (Q5, D-009).

## What was built
- Schema `V1__users_and_refresh_tokens.sql`: `users` (unique index on `lower(email)`, role CHECK),
  `refresh_tokens` (`token_hash` unique). Entities `model/User`, `model/RefreshToken`; repositories.
- `security/JwtService` (jjwt 0.12.7, HS256, claims `sub, email, role, iss, iat, exp`), 15-minute access token.
- `security/JwtAuthenticationFilter`, `security/DatabaseUserDetailsService`, `security/SecurityConfig`.
- `service/AuthService`: `login`, `refresh`, `logout`, `me`. `controller/AuthController`:
  `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `GET /api/auth/me`.
- `config/DevUserSeeder`: one user per role in the `dev` profile only.

## Design points worth remembering
1. **No account enumeration.** Unknown email, wrong password and disabled account all return the same
   `401 "Invalid email or password"`. `InternalAuthenticationServiceException` (e.g. database down) is
   rethrown so an outage is a 500, not a false "wrong password".
2. **Refresh tokens are server-side and rotate.** Opaque 256-bit random value; only its SHA-256 hash is
   stored. Plain SHA-256, not BCrypt: the token has no weak part to brute-force, and lookup is by hash.
3. **Rotation is gated by an atomic `UPDATE ... WHERE revoked_at IS NULL`.** A read-then-write would let
   two concurrent refreshes both succeed. The row count (1 or 0) decides the winner.
4. **Reuse detection.** Presenting an already-retired token revokes every session of that user.
   `@Transactional(noRollbackFor = AuthenticationFailedException.class)` is essential: otherwise the
   revocation is rolled back by the very exception that reports the reuse.
5. **Deactivation cuts refresh immediately** (enabled re-checked on refresh). It does *not* cut an
   already-issued access token before it expires (max 15 min), a known limitation (ARCHITECTURE.md 10).

## What did not work first time
- The generated `contextLoads` test needs a live database, so it was removed (D-012).
- My own test looped `when(mock.authenticate(any())).thenThrow(x)` and failed, since re-stubbing invokes
  the mock that already throws. Fixed with `doThrow(...).when(...)`. (Test bug, not product bug.)
- Boot 4 uses Jackson 3, jjwt-jackson uses Jackson 2. Verified with `mvnw dependency:tree` that both
  resolve (Jackson 2.21.5 and 3.1.5) and that tokens issue and verify: no conflict (D-005).

## Verification
Automated: `JwtServiceTest` 9 (round trip, expiry boundary at 14 vs 16 min, tampered signature, other
secret, other issuer, alg=none, unknown role, garbage), `AuthServiceTest` 13 (issue + hash-only storage,
uniform failure, DB-down not masked, rotation, unknown, reuse, expired, race lost, disabled user, logout
owner/foreign, /me). Live, real Postgres (full table in `docs/TEST_CHECKLIST.md` section 4):
```
POST /api/auth/login  -> HTTP 200 {"accessToken":"eyJhbGciO…","refreshToken":"5DOKb_…","tokenType":"Bearer","expiresIn":900}
POST /api/auth/refresh with R1 (first)  -> 200 new pair
POST /api/auth/refresh with R1 again    -> HTTP 401 "Invalid or expired refresh token"
POST /api/auth/refresh with R2          -> HTTP 401   (burned by reuse detection: proves noRollbackFor works)
4 concurrent refreshes, same token, x3  -> "1 200  3 401" every round
select count(*) from refresh_tokens where token_hash in (<raw tokens>) -> 0
```

## Known limits
No user-management endpoints (A4). No purge job for old `refresh_tokens` rows. No login rate limiting
(K4, P1). No per-user Fabric identity (A2, Phase 3).

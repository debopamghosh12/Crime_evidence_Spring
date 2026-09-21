# Test Checklist

Run before calling anything "done". Each check has the command and the **actual output** from the run
that verified it. If a feature has no check here, add one first, then run it.

Last full run: **2026-09-22, Phase 1 (K1, K3, A1, A3, G1, F1, G4)**. Unit/slice tests: 53 passed, 0 failed.
Live checks: all passed (details below).

Secrets are never written in this file: commands use environment-variable references.

## 0. Environment for live checks

```bash
# once per machine: throwaway values, kept OUTSIDE the repo (never commit them)
export DB_PASSWORD=<random>  BLOCKEVIDENCE_JWT_SECRET=<random, >= 32 chars>
export BLOCKEVIDENCE_DEV_SEED_PASSWORD=<random>  SPRING_PROFILES_ACTIVE=dev
export DB_URL=jdbc:postgresql://localhost:5433/blockevidence     # 5433: 5432 was taken by a local Postgres

docker run -d --name be-postgres -e POSTGRES_DB=blockevidence -e POSTGRES_USER=blockevidence \
  -e POSTGRES_PASSWORD="$DB_PASSWORD" -p 127.0.0.1:5433:5432 -v be-postgres-data:/var/lib/postgresql/data postgres:16
docker run -d --name be-ipfs -p 127.0.0.1:5001:5001 ipfs/kubo:latest
./mvnw spring-boot:run
```
Restart later with `docker start be-postgres be-ipfs`. Remove with `docker rm -f be-postgres be-ipfs` and
`docker volume rm be-postgres-data`.

## 1. Automated tests (all features)

Command: `./mvnw test`

```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.blockevidence.backend.config.JwtPropertiesTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.blockevidence.backend.ledger.HealthIndicatorsTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- in com.blockevidence.backend.security.JwtServiceTest
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0 -- in com.blockevidence.backend.security.SecurityAndErrorFormatTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in com.blockevidence.backend.service.AuthServiceTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.blockevidence.backend.storage.HttpIpfsClientTest
Tests run: 53, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 2. K3: the app refuses to start without required secrets

| Check | Command | Actual |
|---|---|---|
| No JWT secret | `unset BLOCKEVIDENCE_JWT_SECRET; ./mvnw spring-boot:run` | exit 1: `Binding to target com.blockevidence.backend.config.JwtProperties failed: Property: blockevidence.jwt.secret Value: ""` and `Reason: must be at least 32 characters ...` / `Reason: must not be blank` |
| Normal start | `./mvnw spring-boot:run` (all env set) | `Started BlockEvidenceApplication in 9.598 seconds`; Flyway on `PostgreSQL 16.15`; six `Seeded dev user ...` lines |

## 3. K1: one error format everywhere

Every response below has the shape `{timestamp, status, error, message, path, fieldErrors}`.

| Case | Command (`$B`=http://localhost:8080) | Actual |
|---|---|---|
| Validation | `curl -X POST $B/api/auth/login -H 'Content-Type: application/json' -d '{"email":"not-an-email","password":""}'` | `400 {"status":400,"error":"VALIDATION_FAILED","message":"Request validation failed","path":"/api/auth/login","fieldErrors":[{"field":"email","message":"must be a well-formed email address"},{"field":"password","message":"must not be blank"}]}` |
| Malformed JSON | `curl -X POST $B/api/auth/login -H 'Content-Type: application/json' -d '{ nope'` | `400 {"error":"BAD_REQUEST","message":"Malformed or unreadable request",...}` (no parser internals) |
| Unknown route | `curl $B/nope -H "Authorization: Bearer $AT"` | `404 {"error":"NOT_FOUND","message":"Not Found","path":"/nope",...}` |
| Wrong method | `curl $B/api/auth/login -H "Authorization: Bearer $AT"` | `405 {"error":"METHOD_NOT_ALLOWED",...}` |
| 401 from the filter chain | `curl $B/api/auth/me` | `401 {"error":"UNAUTHENTICATED","message":"Authentication required","path":"/api/auth/me",...}` |
| 500 leaks nothing | unit test `unexpectedExceptionIs500WithNoInternalDetail` (controller throws with a JDBC URL in its message) | passed; body is `INTERNAL_ERROR` / "An unexpected error occurred" |
| 501 stub | unit test `stubbedFeatureIs501` | passed; `NOT_IMPLEMENTED` |

## 4. A1: login, tokens, refresh, logout

| Check | Command | Actual |
|---|---|---|
| Valid login | `curl -X POST $B/api/auth/login -H 'Content-Type: application/json' -d '{"email":"judge@blockevidence.local","password":"'$BLOCKEVIDENCE_DEV_SEED_PASSWORD'"}'` | `200 {"accessToken":"eyJhbGciO…","refreshToken":"5DOKb_…","tokenType":"Bearer","expiresIn":900}` |
| Email case-insensitive | same, email `JUDGE@Blockevidence.Local` | `200` with tokens |
| Wrong password | same, password `wrong-password` | `401 {"error":"UNAUTHENTICATED","message":"Invalid email or password",...}` |
| Unknown user (must be identical) | same, `nobody@blockevidence.local` | `401`, byte-for-byte the same message as above |
| Disabled account (`update users set enabled=false ...`) | login with correct password | `401 "Invalid email or password"` (same as wrong password) |
| /me with token | `curl $B/api/auth/me -H "Authorization: Bearer $CT"` | `200 {"id":"f47ff616-...","email":"collector@blockevidence.local","fullName":"Dev COLLECTOR","department":"Development","role":"COLLECTOR"}` |
| /me with garbage token | `curl $B/api/auth/me -H "Authorization: Bearer not.a.jwt"` | `401 UNAUTHENTICATED` |
| Refresh rotates | `POST /api/auth/refresh {"refreshToken":"<R1>"}` | `200`, new pair (`refreshToken":"a09cHS…"`) |
| Reuse of retired token | same request with R1 again | `401 "Invalid or expired refresh token"` |
| Reuse also burns the new token | refresh with R2 (issued by the first refresh) | `401`: revoked by reuse detection |
| Logout needs a token | `POST /api/auth/logout` without Authorization | `401 UNAUTHENTICATED` |
| Logout | `POST /api/auth/logout` with access token + refresh token | `204` |
| Refresh after logout | refresh with the logged-out token | `401` |
| **Concurrent refresh, same token** | 4 parallel `POST /api/auth/refresh` with one token, 3 rounds | every round: `1 200  3 401` (exactly one winner) |

Stored data (`docker exec be-postgres psql -U blockevidence -d blockevidence -c ...`):

```
select email, role, enabled, left(password_hash,7) as pw_prefix, length(password_hash) as pw_len from users
 admin@blockevidence.local            | ADMIN            | t | $2a$10$ | 60      (6 rows, one per role, all BCrypt)

select left(token_hash,12) as hash_prefix, length(token_hash) as hash_len, revoked_at is not null as revoked from refresh_tokens
 670ffc155ce4 | 64 | f     ...     e4861a4fe9c1 | 64 | t   (7 rows; 3 revoked: rotated, reuse-burned, logged-out)

select version, description, success from flyway_schema_history
 1 | users and refresh tokens | t

select count(*) from refresh_tokens where token_hash in (<the raw tokens>)     ->  0
```
The last query shows no raw refresh token is stored, only hashes.

## 5. A3: RBAC scaffolding

Command: `./mvnw test -Dtest=SecurityAndErrorFormatTest`. Uses a test-only controller (`src/test/.../RbacProbeController`); no throwaway endpoint ships.

| Check | Actual |
|---|---|
| `@PreAuthorize("hasRole('ADMIN')")`: COLLECTOR | `403 ACCESS_DENIED` (test `adminRouteIsForbiddenToACollector`) |
| same: ADMIN | `200` |
| `hasAnyRole('JUDGE','AUDITOR')`: JUDGE, AUDITOR | `200` |
| same: COLLECTOR, FORENSIC_ANALYST, PROSECUTOR, ADMIN | `403 ACCESS_DENIED` (all four) |
| Expired / wrong-secret / alg=none / unknown-role token | `401` / `401` / rejected / rejected (`JwtServiceTest`, `SecurityAndErrorFormatTest`) |
| Principal comes only from the token (C-05) | `meUsesTheUserIdFromTheTokenOnly`, `logoutRequiresATokenAndPassesTheTokenOwner` passed |

## 6. G4: health

| Check | Command | Actual |
|---|---|---|
| Anonymous | `curl $B/actuator/health` | `200 {"groups":["liveness","readiness"],"status":"UP"}` |
| ADMIN | `curl $B/actuator/health -H "Authorization: Bearer $AT"` | `200`; `"ipfs":{"status":"UP"}`, `"db":{...,"status":"UP"}`, `"ledger":{"details":{"detail":"FabricLedgerService not implemented (Phase 1 stub); configured channel=crimechannel chaincode=basic"},"status":"UNKNOWN"}`, overall `"status":"UP"` |
| COLLECTOR (non-admin) | same with collector token | `200`, status only (no components) |
| Other actuator endpoints not exposed | `curl $B/actuator/env -H "Authorization: Bearer $AT"` / anonymous | `404 NOT_FOUND` / `401 UNAUTHENTICATED` |
| IPFS stopped | `docker stop be-ipfs`, then ADMIN health | `HTTP 503`; `"ipfs":{"details":{"detail":"IPFS node not reachable"},"status":"DOWN"}` |
| IPFS stopped, anonymous | same, no token | `HTTP 503 {"groups":[...],"status":"DOWN"}` |
| IPFS restarted | `docker start be-ipfs`, ADMIN health | `HTTP 200`, ipfs UP again |
| UNKNOWN ignored when others UP, DOWN still wins | unit test `springAggregationIgnoresUnknownWhenOthersAreUpButNotDown` | passed |
| Kubo POST-only, GET gets 405; slow node times out | `HttpIpfsClientTest` (fake node) | passed; slow node (3 s) answered `false` in < 2 s with a 300 ms timeout |

## 7. G1 / F1: stubs and boundaries

| Check | Actual |
|---|---|
| Every `FabricLedgerService` operation throws `LedgerNotImplementedException` | `HealthIndicatorsTest.everyFabricStubOperationThrowsNotImplemented` passed |
| `HttpIpfsClient` pin/fetch/unpin throw `StorageNotImplementedException` | `HttpIpfsClientTest.pinFetchAndUnpinAreStubsInPhase1` passed |
| C-01: no Fabric import or dependency anywhere | `grep -rnE "^import .*(hyperledger\|fabric)" src` and `grep -ni fabric pom.xml` -> first: no output; second: one match, `pom.xml:15`, the project `<description>` text, and no `<dependency>` (checked by reading the pom). (A plain `grep -ri hyperledger src` DOES match two comment lines in `FabricLedgerService.java` that explain the stub; comments are not imports.) |
| C-01: nothing outside `ledger/` imports ledger types; nothing outside `storage/` imports storage types; controllers import no ledger/storage/repository/model | `grep -rn "backend.ledger" src/main` outside `ledger/` -> none; same for `backend.storage`; `grep -nE "import ...(ledger\|storage\|repository\|model)" controller/*.java` -> none |
| C-05: no request DTO names the acting user | request DTOs are `LoginRequest(email, password)` and `RefreshTokenRequest(refreshToken)`; no actor field |
| C-07: no secrets in the repo | `grep -rniE "unit-test-secret\|0123456789" src` -> no key literals; `application*.yml` secrets are `${DB_PASSWORD}`, `${BLOCKEVIDENCE_JWT_SECRET:}`, `${BLOCKEVIDENCE_DEV_SEED_PASSWORD:}` (env references, empty default); `git check-ignore` confirms `.env`, `*.env`, `application-local.yml` are ignored |

## 8. Not covered yet (known gaps)

- No automated test against a real PostgreSQL (needs Testcontainers, L2). Flyway + Hibernate validation
  are proven only by the live run above.
- No automated regression for the timezone bug (`docs/bugs/jvm-timezone-postgres.md`).
- Purging of expired refresh tokens and per-request "still enabled" check for access tokens do not exist.

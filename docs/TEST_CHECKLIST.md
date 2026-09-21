# Test Checklist

Run before calling anything "done". Each check has the command and the **actual output** from the run
that verified it. If a feature has no check here, add one first, then run it.

Last full run: **2026-09-23, Phase 1 + Phase 2 (B1-B5, C1-C3; G2 is design-only)**. Unit/slice tests:
**117 passed, 0 failed.** Phase 2 live checks passed against the **in-memory reference ledger**, NOT Fabric
(section P2). Phase 1 live checks (sections 2-8 below) were run 2026-09-22 and are unchanged.

Secrets are never written in this file: commands use environment-variable references.

## P2. Phase 2: evidence management (B1-B5, C1-C3) — run 2026-09-23

**Read this first.** Every live result below ran against `InMemoryLedgerService` (profile `memory-ledger`),
real PostgreSQL 16, and a real Kubo 0.43 node in `--offline` mode. It is **not** a Fabric run and proves
nothing about the chaincode. The app logged `*** memory-ledger profile active: ... NOT a blockchain, NOT
tamper-proof ***` at startup. The Fabric run (same checks through `FabricLedgerService`) is a to-do that
starts after `docs/CHAINCODE_DESIGN.md` is approved and implemented.

### P2.1 Automated tests: `./mvnw test`

```
Tests run: 4   JwtPropertiesTest                 Tests run: 12  EvidenceControllerTest (roles, C-05, no DELETE, validation)
Tests run: 5   HealthIndicatorsTest              Tests run: 14  InMemoryLedgerServiceTest (the executable G2 spec)
Tests run: 9   JwtServiceTest                    Tests run: 26  EvidenceServiceTest (register, compensation, C2 tamper, B4, B5)
Tests run: 17  SecurityAndErrorFormatTest        Tests run: 5   HashingInputStreamTest (known-answer SHA-256)
Tests run: 13  AuthServiceTest                   Tests run: 12  HttpIpfsClientTest (fake Kubo: add / cat / pin-rm / probe)
Tests run: 117, Failures: 0, Errors: 0, Skipped: 0     BUILD SUCCESS
```
A first run failed 2 of 117 (`HttpIpfsClientTest.pin...`, `NoClassDefFoundError: org/reactivestreams/Publisher`).
That was a real defect, found by the test and fixed (DECISIONS D-025), not a flaky test.

### P2.2 Live checks: expected versus actual

The full, untrimmed output of the final run is in Appendix P2-A below. "Actual" cells quote it.

| ID | Check | Expected | Actual (final run) |
|---|---|---|---|
| B1 | Register DIGITAL with a file | 201, server-generated `EV-<uuid>`, status COLLECTED, version 1 | `HTTP 201`, `status COLLECTED`, `version 1`, `evidenceId EV-...` |
| C-05 | Metadata carries `"collector":"forged@evil.example"` and `"collectorId":"0000..."` | ignored; creator = JWT user | `createdBy` and `metadata.collectorId` both equal the id from `/api/auth/me`; "createdBy == collector id from JWT? YES" |
| C1 | Ledger `fileSha256` equals an independent hash of the file | equal | `local sha256 ... == ledger fileSha256 ...` (identical 64 hex chars) |
| B2/F1 | Bytes fetched from IPFS by the file CID equal the original | equal | "stored bytes fetched from IPFS by the file CID == original? YES" |
| B1 | Register PHYSICAL (no file) | 201, `fileCid`/`fileSha256` null | `HTTP 201`, `fileCid None`, `fileSha256 None` |
| B3 | By id / file CID / metadata CID | 200 each | `200`, `200`, `200`; unknown CID -> `404 NOT_FOUND`; unknown id -> `404 NOT_FOUND` |
| C2 | Verify untouched evidence | VERIFIED, expected = actual hash | `status VERIFIED`, `file.expectedSha256 == file.actualSha256`, `metadata.result VERIFIED` |
| **C2** | **Deliberately corrupt the file's block on the IPFS node's disk (one word), restart the node, verify** | **TAMPERED** | before `...-original-content`, after `...-0RIGINAL-content`; the node still serves it under the same CID (`cat -> ...0RIGINAL-content`); verify: `status TAMPERED`, `file.expectedSha256 770805fd...` vs `file.actualSha256 01037869...`, `metadata.result VERIFIED` (only the file was touched); `GET ?verify=true` -> `verification.status TAMPERED` |
| **C2** | **Delete the file's block from the node's disk, restart, verify** | **NOT_FOUND** | `removed-block`, then `status NOT_FOUND`, `file.actualSha256 None`, `metadata.result VERIFIED`, in 161 ms |
| C2 | Node stopped -> verify | 503, not a verdict | `HTTP 503 STORAGE_UNAVAILABLE` |
| B4 | PUT update v1->v2 with a reason | version 2, `lastReason` set, old metadata untouched | `version 2`, `lastAction METADATA_UPDATED`, `lastReason Location corrected after audit`, `metadata.metadataVersion 2`, `previousMetadataCid bafk...` |
| B4 | Old version still readable | version 1 shows the old location | `GET /versions/1 -> Desk 2`; `GET /versions/2 -> Locker 9` |
| B4 | Stale update (`expectedVersion 1`, record at 2) | 409 | `409 VERSION_CONFLICT`: "Expected version 1 but the record is at version 2" |
| B4 | Blank reason / JUDGE tries to update | 400 / 403 | `400 VALIDATION_FAILED` / `403 ACCESS_DENIED` |
| C3 | History | one entry per version with tx id and ledger time | `v1 CREATED tx=... at=... by=COLLECTOR`, `v2 METADATA_UPDATED tx=... by=FORENSIC_ANALYST reason='Location corrected after audit'` |
| B5 | PROSECUTOR requests disposal | 200, PENDING, status unchanged | `disposal.state PENDING`, `status COLLECTED`, `lastAction DISPOSAL_REQUESTED` |
| B5 | COLLECTOR tries to approve | 403 | `403 ACCESS_DENIED` |
| B5 | JUDGE approves a stale version | 409 | `409 VERSION_CONFLICT` |
| B5 | JUDGE approves the reviewed version | DISPOSED, version +1 | `status DISPOSED`, `version 3`, `lastAction DISPOSAL_APPROVED`, `lastReason Order verified` |
| B5 | Edit after DISPOSED | rejected | `409 INVALID_STATE`: "Evidence is DISPOSED and can no longer change" |
| B5/C-02 | Record and history survive disposal | still readable | `status DISPOSED` on GET; history `[(1,'CREATED'),(2,'DISPOSAL_REQUESTED'),(3,'DISPOSAL_APPROVED')]` |
| C-02 | DELETE `/api/evidence/{id}` as collector, admin, judge | 405 | `405 METHOD_NOT_ALLOWED` x3 |
| A3 | Register as each role | 201 for COLLECTOR, FORENSIC_ANALYST only | `collector 201`, `forensic-analyst 201`, `prosecutor 403`, `judge 403`, `auditor 403`, `admin 403` |
| B2 | Disallowed type | 415 | `415 UNSUPPORTED_FILE_TYPE`: "File type 'application/x-msdownload' is not allowed" |
| B2 | Empty file for DIGITAL / file on PHYSICAL | 400 | `400 FILE_REQUIRED` / `400 FILE_NOT_ALLOWED` |
| B2 | 60 MB (limit 50 MB) | 413 | `413 CONTENT_TOO_LARGE` |
| B2/C1 | 20 MB image | 201, hash and size match | `HTTP 201 in 987 ms`, ledger sha256 == local sha256, `size=20000000`; verify -> VERIFIED |
| F1 | IPFS stopped: GET evidence | ledger info still returned | `HTTP 200`, `status COLLECTED`, `version 2`, `metadataAvailable False`, `metadata None` |
| F1 | IPFS stopped: register | 503, nothing half-written | `503 STORAGE_UNAVAILABLE` |
| G4 | Health with the reference ledger | overall UP, ledger labelled fake | `overall UP`; `ledger detail "IN-MEMORY reference ledger (dev/test only). NOT a blockchain, NOT tamper-proof."` |

Two harness bugs surfaced on the first run of the script and were fixed before the final run (both mine,
neither the app's): Git Bash rewrote `/data/ipfs/...` arguments to `docker exec`, so the "before/after" lines
were blank and the block deletion silently did nothing (NOT_FOUND showed `VERIFIED`). With
`MSYS_NO_PATHCONV=1` both checks are real. A normalised diff of the two runs showed these were the only differences.

### P2.3 Compensation and safety (automated, `EvidenceServiceTest`)

| Check | Result |
|---|---|
| Ledger rejects the write -> the pins made for it are removed | passed: no orphaned pins, 2 unpinned |
| ...but a file CID another record still references is NEVER unpinned | passed |
| Ledger cannot be asked -> nothing is unpinned ("unsure means keep the data") | passed |
| Update refuses to build on tampered metadata (409 INTEGRITY_CHECK_FAILED) | passed |
| Update with a stale version writes nothing to IPFS | passed |
| Hostile client filenames reduced to a bare name (`..\\..\\x.txt`, `/etc/passwd`, CR/LF) | passed |
| `LedgerService` has no method named delete/remove/purge/erase/destroy | passed |

**P2.3b Constraint and boundary greps, re-run on the Phase 2 code (2026-09-23).** Commands and actual results:

| Rule | Command | Actual |
|---|---|---|
| C-01 no Fabric anywhere | `grep -rnE "^import .*(hyperledger\|fabric)" src`; `grep -ni "<artifactId>.*fabric" pom.xml` | both: no output |
| C-01 only `service/` uses the ledger outside `ledger/` | `grep -rln "backend\.ledger" src/main --include=*.java` minus `/ledger/` | `service/EvidenceService.java`, `service/VerificationService.java` only |
| Controllers see no ledger/storage/repository/model | `grep -nE "import ...(ledger\|storage\|repository\|model)" controller/*.java` | no output |
| DTOs import no ledger/storage/service/controller | `grep -rnE ... dto` | no output |
| `domain/` imports no other application package | `grep -rnE ... domain` | no output |
| `exception/` imports no ledger/storage | `grep -rnE ... exception` | no output |
| `ledger/` and `storage/` import neither controller/service nor each other | `grep -rnE ... ledger storage` | no output (both directions) |
| C-02 no delete anywhere | `grep -rniE "@DeleteMapping\|RequestMethod\.DELETE\|deleteBy\|deleteAll\|\.delete\(" src/main` | no output |
| C-05 request DTOs name no acting user | components of `RegisterEvidenceRequest`, `UpdateEvidenceRequest`, `DisposalRequestBody`, `DisposalDecisionBody` | `caseId,type,description,location,collectedAt,notes`; `expectedVersion,reason,description,location,notes`; `expectedVersion,reason`; `expectedVersion,note`: no collector/officer/actor field |
| C-07 no key literals | `grep -rniE "unit-test-secret\|0123456789abcdef\|password\s*=\s*\"[^\"]{6,}\"" src` | no output |

### P2.4 Not covered yet (known gaps)

- **Everything on Fabric.** The chaincode, `FabricLedgerService`, endorsement, MVCC conflicts, the peer's
  history DB, real transaction ids/timestamps. Blocked on approval of `docs/CHAINCODE_DESIGN.md`.
- The default profile (Fabric stub) answers every evidence call with `501 NOT_IMPLEMENTED`; not run live in Phase 2.
- Memory: a 20 MB upload was tested; streaming/heap behaviour for a 50 MB upload under concurrent load was not.
- No automated test proves the block-corruption scenario (it needs a real Kubo node and disk access); the
  unit-level equivalent uses `FakeIpfsClient.corrupt()`.

## 0. Environment for live checks

```bash
# once per machine: throwaway values, kept OUTSIDE the repo (never commit them)
export DB_PASSWORD=<random>  BLOCKEVIDENCE_JWT_SECRET=<random, >= 32 chars>
export BLOCKEVIDENCE_DEV_SEED_PASSWORD=<random>  SPRING_PROFILES_ACTIVE=dev
export DB_URL=jdbc:postgresql://localhost:5433/blockevidence     # 5433: 5432 was taken by a local Postgres

docker run -d --name be-postgres -e POSTGRES_DB=blockevidence -e POSTGRES_USER=blockevidence \
  -e POSTGRES_PASSWORD="$DB_PASSWORD" -p 127.0.0.1:5433:5432 -v be-postgres-data:/var/lib/postgresql/data postgres:16
# --offline: a default Kubo node joins the PUBLIC IPFS network (DECISIONS D-020). Never run evidence on one.
docker run -d --name be-ipfs -p 127.0.0.1:5001:5001 ipfs/kubo:latest daemon --offline --migrate=true
./mvnw spring-boot:run                       # Phase 1 checks (default profile: ledger stub, UNKNOWN)
SPRING_PROFILES_ACTIVE=dev,memory-ledger ./mvnw spring-boot:run     # Phase 2 checks (reference ledger)
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

> **Superseded in Phase 2 (2026-09-23).** The IPFS client is no longer a stub (see P2 and
> `docs/features/b2-file-upload.md`), and `LedgerService` was finalised (DECISIONS D-016), so the test names
> below (`pinFetchAndUnpinAreStubsInPhase1`, the 7-operation stub check) no longer exist. The C-01/C-05/C-07
> boundary checks still apply and were re-run on the Phase 2 code (see P2.3). Kept for history.

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

## Appendix P2-A. Final live run, verbatim (2026-09-23, memory-ledger profile)

Script: run against `http://localhost:8080`; tokens omitted from the output by design.

```
collector user id (from /api/auth/me): f47ff616-80f2-4b54-b128-3b1b3fb6b8d0

### B1/B2/C1 register DIGITAL evidence (metadata carries a FORGED collector, must be ignored)
HTTP 201
   evidenceId                 EV-42f2f09e-4609-4a87-ba17-15751bf75e1d
   status                     COLLECTED
   version                    1
   createdBy                  f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   currentCustodian           f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   fileCid                    bafkreidxbac73jhkuu4rde55tbr2eagicxifwkbyewjijhr7wautoy2jkq
   fileSha256                 770805fda4eaa5391193bd9863a200c815d05b28382592849e3fb02937634954
   fileSize                   41
   metadataAvailable          True
   metadata.collectorId       f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   verification.status        NOT_CHECKED
   -> local sha256 of the file : 770805fda4eaa5391193bd9863a200c815d05b28382592849e3fb02937634954
   -> ledger fileSha256        : 770805fda4eaa5391193bd9863a200c815d05b28382592849e3fb02937634954
   -> createdBy == collector id from JWT? YES
   -> stored bytes fetched from IPFS by the file CID == original? YES

### B1 register PHYSICAL evidence (no file)
HTTP 201
   evidenceId                 EV-e4631b74-33fc-4c15-bb00-cfb9074d90af
   evidenceType               PHYSICAL
   fileCid                    None
   fileSha256                 None
   status                     COLLECTED
   createdByRole              None

### B3 retrieve by id, by file CID, by metadata CID
200 HTTP by id (AUDITOR)
   status                     COLLECTED
   version                    1
   metadata.description       Suspect phone image
   verification.status        NOT_CHECKED
200 HTTP by file CID
   ids: ['EV-42f2f09e-4609-4a87-ba17-15751bf75e1d']
200 HTTP by metadata CID
404 HTTP by unknown CID
   error                      NOT_FOUND
404 HTTP unknown id
   error                      NOT_FOUND

### C2 verify untouched evidence
200 HTTP
   status                     VERIFIED
   ledgerVersion              1
   file.result                VERIFIED
   file.expectedSha256        770805fda4eaa5391193bd9863a200c815d05b28382592849e3fb02937634954
   file.actualSha256          770805fda4eaa5391193bd9863a200c815d05b28382592849e3fb02937634954
   metadata.result            VERIFIED

### C2 DELIBERATE CORRUPTION: change one word inside the file's block on the IPFS node's disk, then restart the node
   -> block file on the node: /data/ipfs/blocks/SV/CIQHOCAF7WSOVJJZCGJ33GDDUIAMQFOQLMUDQJMSQSPD7MBJG5RUSVA.data
   -> before: LIVE-EVIDENCE-1790018209-original-content
   -> after : LIVE-EVIDENCE-1790018209-0RIGINAL-content
   -> IPFS still answers a cat for the same CID (content-addressing did NOT catch it):
   ->    cat -> LIVE-EVIDENCE-1790018209-0RIGINAL-content
200 HTTP verify
   status                     TAMPERED
   file.result                TAMPERED
   file.expectedSha256        770805fda4eaa5391193bd9863a200c815d05b28382592849e3fb02937634954
   file.actualSha256          010378697d295c54eab93e49f6ab0ca00824da6b10af3bd6742ebc847d086825
   metadata.result            VERIFIED
200 HTTP GET ?verify=true
   verification.status        TAMPERED
   status                     COLLECTED

### C2 NOT_FOUND: register another item, delete its file block from the node's disk, restart
removed-block
200 HTTP verify (161 ms)
   status                     NOT_FOUND
   file.result                NOT_FOUND
   file.actualSha256          None
   metadata.result            VERIFIED

### B4 versioned update (ANALYST), old version stays readable, stale update rejected
200 HTTP update v1->v2
   version                    2
   lastAction                 METADATA_UPDATED
   lastReason                 Location corrected after audit
   metadata.location          Locker 9
   metadata.description       Wallet
   metadata.metadataVersion   2
   metadata.previousMetadataCid bafkreih4qbo27us53rmcee7um6knsd5kyjncs3h7ims7sj25hyighfk7ta
200 HTTP GET version 1 (old)
   version                    1
   metadata.location          Desk 2
200 HTTP GET version 2
   version                    2
   metadata.location          Locker 9
409 HTTP stale update (expectedVersion=1, record is at 2)
   error                      VERSION_CONFLICT
   message                    Expected version 1 but the record is at version 2
400 HTTP blank reason
   error                      VALIDATION_FAILED
403 HTTP JUDGE update
   error                      ACCESS_DENIED

### C3 ledger history (tx ids and ledger timestamps)
200 HTTP
   v1  CREATED           tx=7db40fb63eb7be76..  at=2026-09-21T19:17:10.438778900Z  by=COLLECTOR reason=''
   v2  METADATA_UPDATED  tx=487f4487f0ec20ec..  at=2026-09-21T19:17:10.615247400Z  by=FORENSIC_ANALYST reason='Location corrected after audit'

### B5 disposal: request (PROSECUTOR) -> COLLECTOR cannot approve -> stale approval rejected -> JUDGE approves
200 HTTP request disposal
   status                     COLLECTED
   version                    2
   disposal.state             PENDING
   disposal.reason            Case closed by order 42/2026
   lastAction                 DISPOSAL_REQUESTED
403 HTTP COLLECTOR approve
   error                      ACCESS_DENIED
409 HTTP JUDGE approve with stale version
   error                      VERSION_CONFLICT
200 HTTP JUDGE approve
   status                     DISPOSED
   version                    3
   disposal.state             NONE
   lastAction                 DISPOSAL_APPROVED
   lastReason                 Order verified
409 HTTP update after DISPOSED
   error                      INVALID_STATE
   message                    Evidence is DISPOSED and can no longer change
200 HTTP the DISPOSED record is still readable
   status                     DISPOSED
   version                    3
200   history entries still on ledger: [(1, 'CREATED'), (2, 'DISPOSAL_REQUESTED'), (3, 'DISPOSAL_APPROVED')]

### C-02: there is no delete
   DELETE /api/evidence/{id} as collector -> 405  METHOD_NOT_ALLOWED
   DELETE /api/evidence/{id} as admin -> 405  METHOD_NOT_ALLOWED
   DELETE /api/evidence/{id} as judge -> 405  METHOD_NOT_ALLOWED

### A3 roles: who may register (403 for the rest)
   collector -> 201
   forensic-analyst -> 201
   prosecutor -> 403
   judge -> 403
   auditor -> 403
   admin -> 403

### B2 upload limits and types
   disallowed type (application/x-msdownload) -> 415  UNSUPPORTED_FILE_TYPE | File type 'application/x-msdownload' is not allowed
   empty file for DIGITAL -> 400  FILE_REQUIRED
   file on PHYSICAL evidence -> 400  FILE_NOT_ALLOWED
   missing required metadata field -> 400  ['description', 'caseId']
   60 MB file (limit 50 MB) -> 413  CONTENT_TOO_LARGE
   20 MB file -> HTTP 201 in 987 ms
   -> local sha256 : 9388b3b26e01d0124d475763c1674113659a61e69c7c95fd014ad0ea19d63174
   -> ledger sha256: 9388b3b26e01d0124d475763c1674113659a61e69c7c95fd014ad0ea19d63174   size=20000000
200 HTTP verify of the 20 MB file
   status                     VERIFIED
   file.result                VERIFIED

### F1 IPFS outage: ledger information survives, verify says 503 (not NOT_FOUND)
200 HTTP GET during outage
   status                     COLLECTED
   version                    2
   metadataAvailable          False
   metadata                   None
503 HTTP verify during outage
   error                      STORAGE_UNAVAILABLE
   message                    IPFS node not reachable while reading content
   register during outage -> 503  STORAGE_UNAVAILABLE

### G4 health with the reference ledger
200 HTTP
   overall: UP
   ledger : {'details': {'detail': 'IN-MEMORY reference ledger (dev/test only). NOT a blockchain, NOT tamper-proof.'}, 'status': 'UP'}
   ipfs   : {'status': 'UP'}
```

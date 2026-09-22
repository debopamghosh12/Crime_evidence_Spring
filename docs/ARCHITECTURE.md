# Architecture

> **Status: APPROVED 2026-09-21 (Q1–Q8 resolved, see end of §9).** Phase 1 (Foundation) is the only phase being designed
> in detail; later phases appear only as placeholders so the shape is shared. Feature IDs (K1, A1, G1 ...)
> refer to `docs/FEATURE_LIST.md`.

## 1. What this system is

A Spring Boot REST backend for evidence management. The **ledger** (Hyperledger Fabric, channel
`crimechannel`, chaincode `basic`) is the source of truth for evidence integrity. **IPFS** holds file
content. **PostgreSQL** holds users and, from Phase 4, an off-chain read model for search. Controllers
never talk to Fabric or IPFS directly; everything goes through service-layer interfaces so the Fabric
implementation can later be swapped for web3j/Polygon (G1).

## 2. Baseline

| Item | Choice | Notes |
|---|---|---|
| Language | Java 21 | JDK 21.0.12 is installed. |
| Framework | Spring Boot 4.1.1 | 3.5.x (original default) is no longer offered by start.spring.io. See DECISIONS D-003. |
| Build | Maven (with wrapper) | Logged in DECISIONS.md D-001. Maven is not on PATH here; `mvnw` supplies it. |
| Database | PostgreSQL 16 | Run via `docker run` in Phase 1 (Docker 28.3 is installed). |
| Migrations | Flyway | Schema is versioned SQL, not `ddl-auto`. |
| Base package | `com.blockevidence.backend` | Confirmed (Q2 default). |

## 3. Module layout

Package-by-layer, matching the layout in the feature list. Two supporting packages are added beyond the
six you named: `model` (JPA entities) and `dto`, plus `config` and `exception`. Marked with the phase in
which each piece is built; **only P1 items are in scope now.**

```
com.blockevidence.backend
├── BlockEvidenceApplication
│
├── config/              @ConfigurationProperties records + bean wiring. No logic.        [P1: K3]
│   ├── JwtProperties        blockevidence.jwt.*     (secret from env, access/refresh TTL)
│   ├── IpfsProperties       blockevidence.ipfs.*    (api-url)
│   └── FabricProperties     blockevidence.fabric.*  (channel, chaincode; identity later)
│
├── controller/          HTTP only: bind + @Valid, call ONE service, return a DTO.
│   ├── AuthController       POST /api/auth/login, /refresh   GET /api/auth/me            [P1: A1]
│   └── (Evidence, Case, User controllers ...)                                            [P2+]
│
├── dto/                 Request/response records. Entities never cross the HTTP boundary.
│
├── exception/           ApiError, GlobalExceptionHandler (@RestControllerAdvice),
│                        domain exceptions                                                [P1: K1]
│
├── security/            Cross-cutting. Nothing outside this package parses a token.
│   ├── Role                 COLLECTOR, FORENSIC_ANALYST, PROSECUTOR, JUDGE, AUDITOR, ADMIN [P1: A3]
│   ├── AuthenticatedUser    principal: userId, email, role. The ONLY source of "who is acting"
│   ├── JwtService           issue / parse / validate access tokens                       [P1: A1]
│   ├── JwtAuthenticationFilter                                                           [P1: A1]
│   ├── SecurityConfig       filter chain, @EnableMethodSecurity, public routes           [P1: A1/A3]
│   └── ApiAuthenticationEntryPoint / ApiAccessDeniedHandler   (401 / 403 in ApiError form)
│
├── service/             Business rules. Depends on repository, LedgerService, IpfsClient.
│   ├── AuthService          login, refresh, logout                                       [P1: A1]
│   └── (EvidenceService, VerificationService, CustodyService, ...)                       [P2+]
│
├── repository/          Spring Data JPA interfaces only.
│   ├── UserRepository, RefreshTokenRepository                                            [P1]
│
├── model/               JPA entities.
│   ├── User                 id (UUID), email (unique), passwordHash (BCrypt), fullName,
│   │                        department, role, enabled, createdAt                          [P1: A1]
│   └── RefreshToken         id, userId, tokenHash, expiresAt, revokedAt                  [P1: A1]
│
├── ledger/              The ONLY package that may know Fabric exists.
│   ├── LedgerService        interface (see 4.1)                                          [P1: G1]
│   ├── FabricLedgerService  @Service stub: every method throws LedgerNotImplementedException,
│   │                        except health(), which reports UNKNOWN         [P1: G1]
│   ├── Ledger* records      LedgerEvidenceRecord, LedgerTxResult, LedgerHistoryEntry
│   └── LedgerHealthIndicator   calls LedgerService.health()                             [P1: G4]
│
└── storage/             The ONLY package that may know IPFS exists.
    ├── IpfsClient           interface: pin, fetch, unpin, isReachable                    [P1: F1]
    ├── HttpIpfsClient       RestClient; only isReachable() is real in Phase 1,
    │                        pin/fetch/unpin throw StorageNotImplementedException          [P1: F1]
    └── IpfsHealthIndicator  calls IpfsClient.isReachable()                               [P1: G4]
```

### Dependency rules (what may call what)

```
controller ──► service ──► repository
                  │  ├───► ledger  (via LedgerService interface only)
                  │  └───► storage (via IpfsClient interface only)
security  ◄── used by controller (principal) and config; never imports service/ledger/storage
domain    ◄── pure shared types (enums, Cid, EvidenceMetadata); imported by dto, service, ledger, storage; imports none of them
ledger, storage, repository  never import controller, service, or each other
exception ◄── ApiException base; ledger/ and storage/ extend it, exception/ never imports them
```

Rules 1 and 2 restate CLAUDE.md hard constraints: no Fabric call outside `ledger/`, and controllers
never see `ledger/` or `storage/` types.

## 4. Phase 1 in detail

### 4.1 `LedgerService` (G1)

The seam that makes the Fabric ↔ Polygon swap possible. Phase 1 defines it and provides the stub.

Operations, taken from the chaincode function list in G2 (**signatures provisional, expect revision in
Phase 2 when real chaincode calls force the shapes**): `createEvidence`, `updateEvidence`,
`updateStatus`, `initiateTransfer`, `acceptTransfer`, `getEvidence`, `getHistory`, plus
`health()` for G4 (returns `LedgerHealth`: UP / DOWN / UNKNOWN, so the stub can honestly say UNKNOWN). `rejectTransfer` waits for Phase 3 (D2).

`FabricLedgerService` in Phase 1 has **no `fabric-gateway` dependency**: it imports nothing from Fabric.
The dependency arrives when the class is actually implemented.

### 4.2 Global error format (K1)

Every non-2xx response has the same body, including 401 and 403 produced by the security filters, which
`@RestControllerAdvice` cannot see, hence the two custom handlers in `security/`.

```json
{
  "timestamp": "2026-09-21T17:58:00Z",
  "status": 400,
  "error": "VALIDATION_FAILED",
  "message": "Request validation failed",
  "path": "/api/auth/login",
  "fieldErrors": [ { "field": "email", "message": "must be a well-formed email address" } ]
}
```

`error` is a stable machine code. Ours: `VALIDATION_FAILED`, `UNAUTHENTICATED`, `ACCESS_DENIED`,
`NOT_IMPLEMENTED`, `INTERNAL_ERROR` (no stack trace, no exception message). For errors raised by Spring MVC
itself the code is the HTTP status name (`BAD_REQUEST`, `NOT_FOUND`, `METHOD_NOT_ALLOWED` ...).
`LEDGER_UNAVAILABLE` is reserved for Phase 2, when a real ledger can be unavailable. `fieldErrors` is always
present (empty unless VALIDATION_FAILED). A `correlationId` field is added with K6
(P2), not now.

### 4.3 Authentication and RBAC (A1, A3)

- **Login:** email + password, BCrypt. Unknown email and wrong password return the *same* 401, so
  accounts cannot be enumerated. Disabled users cannot log in.
- **Access token:** JWT, HS256, 15 min (configurable). Claims: `sub` = user UUID, `role`. Signing secret
  ≥ 256 bits from env var, never in the repo.
- **Refresh token:** opaque random value, only its SHA-256 hash stored in `refresh_token`. Rotated on
  every use; revocable; refresh re-checks `user.enabled`. (Chosen over a stateless refresh JWT because
  deactivating a user, A4, must take effect immediately; see open decision Q3.)
- **RBAC scaffolding:** `Role` enum → Spring authority `ROLE_<NAME>`; `@EnableMethodSecurity` so later
  controllers use `@PreAuthorize`. Phase 1 ships **no permission matrix**, only the mechanism. Who may
  do what to which evidence is defined per feature (D1, B5, ...), because the same rules must be
  duplicated in the chaincode (A3) and belong next to it.
- **Verification of RBAC** uses a test-only controller under `src/test`, so no throwaway endpoint ships.
- `AuthenticatedUser` is the only way service code learns who is acting (needed later by A2: the officer
  on the ledger always comes from the token, never the request body).

### 4.4 Health checks (G4)

`GET /actuator/health` reports three components: `db` (Spring built-in), `ipfs`, `ledger`.

- `ipfs`: `HttpIpfsClient.isReachable()` calls the Kubo RPC `POST {api-url}/api/v0/version` (Kubo's RPC
  API is POST-only; **confirmed** against a real Kubo node and a fake-Kubo unit test that answers GET with 405). UP / DOWN.
- `ledger`: the stub reports **UNKNOWN** with detail `"FabricLedgerService not implemented (Phase 1
  stub)"`. Spring's default aggregation ignores UNKNOWN when other components are UP, so overall health
  stays honest without the ledger stub making the whole app look broken. **Confirmed** by unit test and live run (overall UP, ledger UNKNOWN; overall 503 when IPFS is stopped).
- Public: status only. Component details visible to `ADMIN` (`show-details: when-authorized`).
- Only `health` is exposed under `/actuator` in Phase 1.

### 4.5 Configuration (K3)

Profiles: `dev`, `test`, `prod`. All secrets and endpoints come from environment variables:

| Property | Env var | Default |
|---|---|---|
| `spring.datasource.url/username/password` | `DB_URL`, `DB_USER`, `DB_PASSWORD` | dev: local Postgres URL; no default password |
| `blockevidence.jwt.secret` | `BLOCKEVIDENCE_JWT_SECRET` | **none**: app refuses to start without it |
| `blockevidence.jwt.access-ttl` / `refresh-ttl` | | `15m` / `7d` |
| `blockevidence.ipfs.api-url` | `IPFS_API_URL` | `http://localhost:5001` |
| `blockevidence.fabric.channel` / `chaincode` | `FABRIC_CHANNEL`, `FABRIC_CHAINCODE` | `crimechannel` / `basic` |

### 4.6 Phase 1 endpoint surface

| Method + path | Auth | Purpose |
|---|---|---|
| `POST /api/auth/login` | public | email/password → access + refresh token |
| `POST /api/auth/refresh` | public (refresh token) | rotate refresh token, new access token |
| `POST /api/auth/logout` | authenticated | revoke the presented refresh token |
| `GET /api/auth/me` | authenticated | id, email, role, department (proves the token → principal path) |
| `GET /actuator/health` | public (details: ADMIN) | G4 |

No evidence endpoints exist in Phase 1.

## 5. Data flow

### 5.1 Login (Phase 1)
```
Client ─► AuthController.login(@Valid LoginRequest)
            └─► AuthService.login
                  ├─► AuthenticationManager (DaoAuthenticationProvider + BCrypt)
                  │      └─► UserRepository.findByEmail
                  ├─► JwtService.issueAccessToken(user)
                  └─► RefreshTokenRepository.save(hash of new opaque token)
            ◄── LoginResponse { accessToken, refreshToken, expiresIn }
```

### 5.2 Authenticated request (Phase 1)
```
Client ─► JwtAuthenticationFilter ─ parse + verify JWT ─► SecurityContext(AuthenticatedUser)
            ─► @PreAuthorize check ─► Controller ─► Service ...
   any failure: ApiAuthenticationEntryPoint (401) / ApiAccessDeniedHandler (403) ─► ApiError JSON
```

### 5.3 Health (Phase 1)
```
GET /actuator/health ─► DataSourceHealthIndicator
                     ─► IpfsHealthIndicator   ─► IpfsClient.isReachable()
                     ─► LedgerHealthIndicator ─► LedgerService.health()   (stub → UNKNOWN)
```

### 5.4 Register evidence (TARGET, Phase 2, not built; shown so the seams make sense)
```
EvidenceController ─► EvidenceService
   1. SHA-256 the file (streaming)              C1
   2. IpfsClient.pin(file) → CID                F1/B2
   3. LedgerService.createEvidence(id, cid, hash, officer-from-token)   [retry on MVCC, F5]
   4. if step 3 fails: IpfsClient.unpin(cid)    F5 compensation
```
Order matters: the ledger write is last so a failure never leaves a ledger entry pointing at a file that
was never stored.

## 6. Scope notes and things I noticed in the feature list

1. **K3 (configuration and secrets)** is in the PDF's Phase 1 list but not in your Step 4 bullets. I
   treat it as part of project setup and include it.
2. **A4 (admin user management) and A5 (case-level access)** appear in no build phase. Phase 1 still
   needs users to exist to log in, see Q5.
3. The PDF's Phase 1 says "LedgerService with Fabric"; your instruction says an unimplemented stub. I'm
   following yours, hence no `fabric-gateway` dependency yet.
4. **Not in Phase 1, deliberately:** K2 Swagger (Phase 5), K4 rate limiting, K5 CORS/HTTPS/headers,
   K6 structured logging, and the whole Phase 2+ surface. K5 in particular means CORS stays at Spring's
   default (cross-origin blocked) until then.
5. **No git repository exists here**, so `docs/ROLLBACK.md` has nothing to point at. See Q1.

## 7. Proposed dependencies (each needs your OK, then a DECISIONS.md entry)

| Dependency | Scope | For | Rejected alternative |
|---|---|---|---|
| `spring-boot-starter-webmvc` (Boot 4 name) | compile | REST; also provides `RestClient` for IPFS (no extra HTTP lib) | WebFlux (nothing here needs reactive) |
| `spring-boot-starter-validation` | compile | K1 | none |
| `spring-boot-starter-security` | compile | A1, A3 | hand-rolled filters |
| `spring-boot-starter-data-jpa` | compile | A1 User entity; later H1 Specifications (named in the feature list) | JDBC/jOOQ |
| `postgresql` | runtime | DB | H2 (drifts from Postgres behaviour) |
| `spring-boot-starter-flyway` + `flyway-database-postgresql` | compile/runtime | versioned schema | `ddl-auto=update`: silent drift, weak audit story for an evidence system |
| `jjwt-api` / `jjwt-impl` / `jjwt-jackson` (0.12.7) | compile/runtime | A1 (named in the feature list) | `spring-security-oauth2-resource-server` (heavier, built for external IdPs) |
| `spring-boot-starter-actuator` | compile | G4 | custom `/health` controller |
| Boot 4 per-starter test starters (`spring-boot-starter-*-test`, generated by Initializr) | test | L1 | none |

Deliberately **not** added yet: `fabric-gateway` (stub needs none), Testcontainers/WireMock (L2, Phase
5), springdoc (K2), Bucket4j (K4), Spring Retry (F5).

## 8. Verification plan for Phase 1 (will become TEST_CHECKLIST.md entries)

Unit tests (Mockito) for `AuthService` and `JwtService`; `@WebMvcTest` slices for 400/401/403 body shape
and RBAC via the test-only controller; then a live run against real Postgres and a real Kubo container
with `curl` output pasted into the checklist: login OK, wrong password, expired/garbage token, refresh
rotation and reuse rejection, `/actuator/health` with IPFS up, IPFS stopped, and ledger UNKNOWN.

## 9. Decisions I need from you

Defaults are what I'll do unless you say otherwise; reply with only the ones you want changed.

| # | Question | My default | Alternative |
|---|---|---|---|
| Q1 | `git init` + baseline commit of the docs before any code, so ROLLBACK.md can name a revert target? | **Yes** | Skip; ROLLBACK.md stays vague |
| Q2 | Base package / Maven `groupId` | `com.blockevidence.backend` | your college/personal namespace |
| Q3 | Refresh tokens | DB-stored, rotating, revocable | Stateless refresh JWT (simpler, cannot be revoked) |
| Q4 | `LedgerService` scope now | Full G2 operation list, provisional | `health()` only, add operations in Phase 2 |
| Q5 | How do users exist before A4? | Dev-profile-only seeder: one user per role, password from env var `BLOCKEVIDENCE_DEV_SEED_PASSWORD`; no user-creation endpoint | Pull a minimal `POST /api/admin/users` (part of A4) into Phase 1, which is outside the phase |
| Q6 | Ledger health while stubbed | UNKNOWN | DOWN (honest but turns overall health red) |
| Q7 | Dependencies in section 7 | approve as listed | strike or swap any row |
| Q8 | Spring Boot line | 3.5.x | 4.0.x, newer but I'd verify library compatibility (jjwt, Flyway module names) first |

### Resolution (2026-09-21)

Q1 done (git initialised, baseline tag `baseline-docs`). Q2, Q3, Q4, Q6, Q7: defaults kept. Q5: approved
as stated (dev-only seeder, no user-creation endpoint). **Q8 changed:** Spring Boot **4.1.1**, because
3.5.x is no longer offered by start.spring.io (D-003). Consequence: Boot 4 renamed/split several starters
and moved some Actuator packages, so the artifact names in §7 are indicative; the generated `pom.xml`
is authoritative. K3 stays in Phase 1. Project generation: Maven, via start.spring.io (the service
IntelliJ's Spring Boot wizard calls).

## 10. As built (Phase 1): deviations from the draft above

Phase 1 was implemented 2026-09-22. Where the code differs from sections 3-9, the code is authoritative
and the difference is listed here. Verification evidence: `docs/TEST_CHECKLIST.md`.

- **Ledger health is `LedgerService.health()` returning `LedgerHealth`, not `isReachable()`**, because a
  boolean cannot express UNKNOWN. `IpfsClient.isReachable()` stays boolean (UP/DOWN only).
- **Extra classes not in the section 3 tree:** `config/ClockConfig` (injectable Clock),
  `config/DevUserSeeder` (dev profile only, Q5), `exception/ApiErrorWriter` (writes ApiError from the
  security filters), `exception/AuthenticationFailedException`, `exception/FeatureNotImplementedException`
  (base of the two stub exceptions, so the exception handler need not import ledger/storage),
  `security/DatabaseUserDetailsService`, `ledger/LedgerHealth`, `ledger/LedgerTxResult`,
  `ledger/LedgerHistoryEntry`, `ledger/LedgerEvidenceRecord`, DTOs `LoginRequest`, `RefreshTokenRequest`
  (shared by refresh and logout), `TokenResponse`, `MeResponse`.
- **JVM default timezone is pinned to UTC in `main()`** (bug: `docs/bugs/jvm-timezone-postgres.md`).
- **Spring Boot 4.1.1**, not 3.5.x (D-003). Boot 4 moved health classes to
  `org.springframework.boot.health.contributor` and uses Jackson 3 (`tools.jackson`) for MVC; jjwt's
  Jackson 2 coexists on the classpath without conflict (checked with `dependency:tree`).
- **`JwtAuthenticationFilter` is constructed with `new` inside `SecurityConfig`, not a bean**, so Boot
  does not register it a second time as a plain servlet filter.
- **Known limitation:** an access token stays valid until it expires (15 min) even if the user is
  deactivated or their role changes. Refresh is cut off immediately. Revisit when A4 lands: either a
  per-request enabled check or a shorter access TTL.
- **Known limitation:** expired/revoked `refresh_tokens` rows are never purged (no housekeeping job yet).
- **No `@SpringBootTest` context test in Phase 1.** It needs a real database; Testcontainers arrives with
  L2 (Phase 5). Coverage instead: unit tests, `@WebMvcTest` slice with the real security chain, and the
  live run recorded in the checklist. See D-012.

## 11. As built (Phase 2): evidence management, 2026-09-22

Phase 2 is implemented and verified live against BOTH the in-memory reference ledger and the real Fabric ledger
(section 12 below); the chaincode design was approved 2026-09-22.

**New packages/classes.** `domain/` (EvidenceStatus, EvidenceType, VerificationStatus, Cid, EvidenceMetadata);
`ledger/` (final `LedgerService`, `LedgerActor`, `LedgerNewEvidence`, `LedgerEvidenceRecord`,
`LedgerHistoryEntry`, `LedgerAction`, `LedgerErrorCode`, `LedgerException`, `InMemoryLedgerService`);
`storage/` (final `IpfsClient`, real `HttpIpfsClient`, `ContentReader`, `ContentNotFoundException`,
`StorageUnavailableException`); `service/` (`EvidenceService`, `VerificationService`, `Sha256`,
`HashingInputStream`); `controller/EvidenceController`; `security/Permissions`; `exception/ApiException`;
`config/UploadProperties`. Final interface signatures and the reasons they changed: DECISIONS D-016.

**Endpoint surface added**

| Method + path | Roles | Feature |
|---|---|---|
| `POST /api/evidence` (multipart: `metadata` JSON + optional `file`) | COLLECTOR, FORENSIC_ANALYST | B1, B2, C1 |
| `GET /api/evidence/{id}[?verify=true]` | any authenticated | B3 |
| `GET /api/evidence/by-cid/{cid}` | any authenticated | B3 |
| `GET /api/evidence/{id}/versions/{n}` | any authenticated | B4 |
| `GET /api/evidence/{id}/history` | any authenticated | C3 |
| `GET /api/evidence/{id}/verify` | any authenticated | C2 |
| `PUT /api/evidence/{id}` | COLLECTOR, FORENSIC_ANALYST | B4 |
| `POST /api/evidence/{id}/disposal` | COLLECTOR, PROSECUTOR | B5 |
| `POST /api/evidence/{id}/disposal/approve` and `/reject` | JUDGE | B5 |
| `DELETE` anything under `/api/evidence` | nobody: 405 | C-02 |

**Configuration added:** `UPLOAD_MAX_FILE_SIZE` (50MB), `UPLOAD_MAX_REQUEST_SIZE` (52MB),
`IPFS_TRANSFER_TIMEOUT` (60s), `IPFS_LOOKUP_TIMEOUT` (10s); profile `memory-ledger`; allow-list
`blockevidence.upload.allowed-content-types`.

**Known limitations (all also in the feature write-ups):**
1. (Resolved 2026-09-22) The default profile now uses the real Fabric ledger; without a configured identity evidence calls answer 503.
2. IPFS content is not private (D-020); a default Kubo node joins the public network. Dev uses `--offline`.
3. Read access is not restricted by case (A5 unscheduled): any authenticated role reads any evidence.
4. The upload type check trusts the client-declared content type (D-026).
5. Retry on Fabric MVCC conflicts (F5) is not built; only compensation is (D-024).
6. Metadata documents may contain descriptive text readable by anyone with the CID (F2 not built).

## 12. As built (Phase 2, ledger stage): chaincode and FabricLedgerService, 2026-09-22

- **`chaincode/evidence/`** (Go, `fabric-contract-api-go` 1.2.2), deployed to channel `crimechannel` as `evidence` v1.1 seq 2 on the revived
  Fabric 2.5.15 network (2 orgs, LevelDB, both must endorse). See `docs/CHAINCODE_DESIGN.md` (section 10 as-built), `docs/FABRIC_RUNBOOK.md`.
- **`ledger/FabricLedgerService`** is now the real implementation (Fabric Gateway 1.12.1, gRPC 1.83.1, TLS to `localhost:7051`), with
  `ledger/FabricErrors` and an extended `config/FabricProperties` (identity by file path). It remains the only class importing Fabric types (C-01).
  The `LedgerNotImplementedException` stub exception was removed.
- **Layout addition:** top-level `chaincode/` (Go module, `scripts/`), `scripts/live/`, `src/test/resources/fabric/` (real peer output as fixtures).
- **New dependencies (approved G6, logged D-030):** `fabric-gateway`, `grpc-netty-shaded` (Java); Go modules for the chaincode.
- **Endpoint surface:** unchanged from section 11.
- **Limitations added:** C-08 (role trusted from the backend until A2); orphaned IPFS pins are possible during a ledger outage (D-037);
  ledger test data is permanent; one peer connection (no failover to Org2's peer).

## 13. As built (Phase 3): status, custody, cases; A2 designed only, 2026-09-22

- **Chaincode** `evidence` now v1.2 sequence 3 (additive, D-039): `InitiateTransfer`, `AcceptTransfer`, `RejectTransfer`, `CancelTransfer`, `FindPendingTransfers`; record gains `transfer`; `canHoldCustody` role set; 33 Go tests. Function list is pinned by a test (there is still no delete function).
- **`LedgerService`** gained `initiateTransfer / acceptTransfer / rejectTransfer / cancelTransfer / findPendingTransferIds` (additive; Phase 2 methods unchanged). Implemented by `FabricLedgerService` (only class importing Fabric) and the in-memory reference ledger, which stays scenario-for-scenario equal to the chaincode. `LedgerEvidenceRecord` gained `Transfer`; an absent value means NONE.
- **New Spring code (additive; no Phase 2 controller or service was edited):** `EvidenceLifecycleController` (status, transfers, pending, chain-of-custody), `CaseController`; `StatusService`, `CustodyService`, `CaseService`; `CaseFile`/`CaseMember` entities and repositories; DTOs; `domain/CaseStatus`, `domain/CaseRole`; permissions `CHANGE_STATUS`, `HOLD_CUSTODY`, `MANAGE_CASES`; Flyway `V2__cases.sql`.
- **Endpoint surface added:** `POST /api/evidence/{id}/status`; `POST /api/evidence/{id}/transfers[/accept|/reject|/cancel]`; `GET /api/transfers/pending`; `GET /api/evidence/{id}/chain-of-custody`; `POST/GET /api/cases`, `GET/PUT /api/cases/{id}`, `POST /api/cases/{id}/members`, `DELETE /api/cases/{id}/members/{userId}`.
- **Authentication of ledger writes is unchanged:** still the single backend identity (Org1 admin cert) with the role passed as an argument (C-08). A2 is a design awaiting the owner (`docs/A2_IDENTITY_DESIGN.md`); nothing was implemented, and B1-B5 authenticate exactly as before.
- **Not built:** E3 (owner's choice pending), A2 (design approved first), D4 (optional), a user-directory endpoint, case-level read access (A5).
- **Dependencies:** none added in Phase 3. A2 would add BouncyCastle `bcpkix-jdk18on` (needs approval, C-04).

## 14. As built (Phase 3, E3): evidence linked to cases, 2026-09-22

- Flyway `V3__case_evidence_links.sql` (table `case_evidence`, unique on `evidence_id`); model `CaseEvidenceLink`; repository
  `CaseEvidenceLinkRepository`.
- **`EvidenceService` now depends on `CaseFileRepository`, `CaseEvidenceLinkRepository` and `Clock`** (new constructor
  params; this is the one place Phase 3 changed a Phase 2 class, done with the owner's explicit approval). `register` now
  requires the caseId to name an existing case; every other Phase 2 behaviour is unchanged (proved by the unchanged P2/P2-F
  regression, TEST_CHECKLIST P3.4/P3-R apart from the added case-creation step).
- `CaseService` gained a dependency on `EvidenceService` (case -> evidence, one direction, no cycle) for the new
  `GET /api/cases/{id}/evidence` endpoint; `CaseResponse` gained `evidenceIds`.
- The ledger and its chaincode are completely unchanged by E3 (still v1.2 seq 3): E3 is entirely off-chain (C-06).

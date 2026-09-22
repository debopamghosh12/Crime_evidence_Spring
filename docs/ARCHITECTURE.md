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

## 15. As built (A2): per-user Fabric identity, 2026-09-22

- **Chaincode `evidence` v1.3 sequence 4** (additive to the record shape; `authorise()` is the only function whose
  logic changed, exactly as CHAINCODE_DESIGN.md section 5 predicted). Deployed only after every enabled dev user was
  enrolled (mandatory order, docs/FABRIC_RUNBOOK.md section 8).
- **New `ledger/` classes:** `IdentityStore` (interface), `WalletIdentity` (record), `FileWalletIdentityStore`
  (file-backed wallet, `!memory-ledger` profile), `WalletHealthIndicator` (`/actuator/health` component `wallet`).
- **`FabricLedgerService`:** reads and the health probe still use the single SERVICE identity (unchanged,
  `FABRIC_CERT_PATH`/`FABRIC_KEY_PATH`); every WRITE is now signed with the acting user's own identity from
  `IdentityStore`, over a small bounded cache of per-user `Gateway`s sharing one gRPC channel with the service
  identity. A user with no wallet entry gets `403 LEDGER_IDENTITY_MISSING` before any chaincode call.
- **`FabricProperties`** gained `walletDir`/`walletPassphrase` (both optional; blank wallet means every write is
  refused, exactly like an unenrolled user - not a startup failure).
- **`LedgerErrorCode`** gained `LEDGER_IDENTITY_MISSING` (403), raised only by the Java side, never by the chaincode.
- **Unchanged, by design (A2-Q1, confirmed with the owner before implementing):** `LedgerService` interface,
  `LedgerActor`, every Phase 1-3 controller and service, DTOs, the JWT/auth flow, `Permissions`, `EvidenceService`,
  `EvidenceController`. The acting user is still built from the JWT alone (C-05); nothing about how B1-B5 or D1-E3
  endpoints authenticate a REQUEST changed. What changed is how the LEDGER authenticates the WRITE that request causes.
- **New operator scripts (not run by the application):** `scripts/fabric/bootstrap_registrar.sh` (one-time),
  `scripts/fabric/enroll_users.sh` (idempotent, run whenever a user needs a wallet entry),
  `chaincode/scripts/verify_a2_cutover.sh` (verification only).
- **New dependency:** `bcpkix-jdk18on` (already transitively present via `fabric-gateway`; declared explicitly,
  DECISIONS D-049, C-04).
- **Residual, not closed by A2 (constraint C-08, revised wording):** the backend still custodies every user's private
  key; a compromised backend host can sign as any enrolled user. See docs/A2_IDENTITY_DESIGN.md section 4 and
  docs/KNOWN_GAPS.md.

## 16. As built (Phase 4, G3): ledger event sync, 2026-09-22

- **New `ledger/` types** (C-01, only `ledger/` imports Fabric types): `LedgerEventSource` (interface),
  `LedgerEventStream`, `LedgerEvidenceEvent`, `Checkpoint`. `FabricLedgerService` implements `LedgerEventSource`
  in addition to `LedgerService`, reusing its existing service-identity connection (reads/event streams need no
  per-user identity, A2 does not apply). `!memory-ledger`-scoped, same as A2's `IdentityStore`.
- **New `sync/` package** (never imports Fabric types): `EventSyncListener` (the background loop, design sections
  6/9), `EventProcessor` (the atomic per-event unit of work, design section 5), `EventSyncHealthIndicator`
  (`/actuator/health` component `eventSync`), JPA entities/repositories `EvidenceActivity`, `EvidenceProjection`,
  `LedgerSyncCheckpoint`.
- **Flyway `V4__event_sync.sql`**: `ledger_sync_checkpoint` (one row), `evidence_activity` (append-only, `tx_id`
  UNIQUE - the idempotency log and the H3 feed source in one table), `evidence_projection` (current state, for H1/H2).
- **Full design, including the consistency guarantees:** `docs/G3_SYNC_DESIGN.md` (owner-approved before
  implementation, including an added backoff/health section, section 9).
- **Verified live:** a first-ever run replayed the whole ledger from block 0 (164 activity rows / 90 projection
  rows in one pass); a write while the listener was running synced within seconds; two writes made directly to
  the chaincode (bypassing Spring, proven by writing them while the whole application was stopped) were both
  present with correct txIds and the checkpoint caught up after a restart, with zero duplicates; a further
  redundant restart with no new writes left every count unchanged. Full output: TEST_CHECKLIST P4-G3.
- **Not built yet:** H1-H4 (read from this schema) and A6 (independent of it). Nothing in Phase 1-3 changed.

## 17. As built (Phase 4, H1-H4, A6): off-chain sync consumers, 2026-09-22

- **New `service/` classes** (read from G3's schema, never from `LedgerService`): `SearchService` (H1),
  `DashboardService` (H2), `ActivityService` (H3). New `notification/` package (H4): `Notification`,
  `NotificationType`, `NotificationRepository`, `NotificationService` - called from `sync.EventProcessor` (inside
  its transaction) and directly from `EvidenceService` (tamper alerts, independent of G3). New `audit/` package
  (A6): `AuditAction`, `AuditLog`, `AuditLogRepository`, `AuditSpecifications`, `AuditService`.
- **Endpoint surface added:** `GET /api/evidence/search` (H1); `GET /api/dashboard` (H2); `GET /api/activity`
  (H3); `GET /api/notifications`, `POST /api/notifications/{id}/read` (H4, always the caller's own); `GET
  /api/audit` (A6, `Permissions.READ_AUDIT_LOG` = ADMIN/AUDITOR only - narrower than ordinary evidence reads).
- **Flyway `V5__h_features.sql`**: `notifications`, `audit_log`.
- **Touched, additively, with the owner's standing Phase-4 approval:** `EvidenceController` (gained `SearchService`
  and `AuditService`, and two lines in `get`/`verify` recording VIEW/DOWNLOAD), `EvidenceService` (gained
  `NotificationService`, and a tamper-alert check after `verification.verify`), `sync.EventProcessor` (gained
  `NotificationService`, called after the checkpoint advance), `ApiAuthenticationEntryPoint`/
  `ApiAccessDeniedHandler`/`GlobalExceptionHandler` (each gained `AuditService` and now records a denied-attempt
  row). No Phase 1-3 BEHAVIOUR changed (verified: the whole Phase 2 checklist re-run, identical).
- **Verified live** (docs/TEST_CHECKLIST.md P4-H): a case with two evidence items, a custody transfer, a status
  change and a full disposal cycle, then H1 search by case/status/text/officer with pagination, H2 aggregate
  counts, H3's ordered feed, H4 notifications for the transfer receiver and every case member plus a live tamper
  alert (a corrupted IPFS block, `verify=true` -> TAMPERED -> notification), and A6's full set including the
  specific deactivation-visibility demonstration the owner required.

## 18. As built (Phase 5, F2/F3): envelope encryption and key management, 2026-09-22

Full design: `docs/F2_F3_ENVELOPE_ENCRYPTION_DESIGN.md` (owner-approved before any code was written). F4's audit
(`docs/features/f4-personal-data-off-chain-audit.md`) established the exposure F2 closes: not the ledger (already
clean), IPFS file/metadata *content*.

- **New `crypto/` package**: `AesGcmCodec` (stateless AES-256-GCM, byte[] and streaming forms, `IV || ciphertext ||
  tag` wire format), `MasterKey` (decodes and length-validates `EncryptionProperties.masterKey` at startup - the
  app refuses to start on a bad value, same as the JWT secret), `UserKeyPair`/`UserKeyPairRepository` (one RSA-2048
  keypair per user; the private key is PKCS8 DER encrypted under the master key), `UserKeyService` (provision/
  publicKey/privateKey), `EvidenceContentKey`/`EvidenceContentKeyRepository` (one RSA-OAEP-SHA256-wrapped copy of
  an evidence item's AES-256 content key, ECK, per authorised user), `ContentKeyService` (generate/wrap/re-wrap/
  revoke/unwrap - see the design doc section 5 for exactly how re-wrap recovers the ECK via any existing holder's
  key, without ever persisting the raw ECK).
- **`config/EncryptionProperties`**: one new env var, `BLOCKEVIDENCE_ENCRYPTION_MASTER_KEY` (base64 AES-256, no
  default - C-07). **CONSTRAINTS.md C-10** names the resulting trust boundary explicitly (owner-approved
  alongside the design).
- **`EvidenceService` changes**: `register()` generates the ECK, encrypts the file (streamed, chained onto the
  existing `HashingInputStream`/C1 machinery - the hash pinned and recorded on the ledger is now of the
  CIPHERTEXT, not the plaintext) and every metadata version, and wraps the ECK for the registrant (mandatory,
  inside the pre-ledger try/compensate block) and every current case member (best-effort). `update()` unwraps the
  ECK via the ACTING user's own wrapped copy before it can write a new metadata version - a user with none gets a
  new 403 `KEY_NOT_AUTHORISED` (a real, flagged behaviour change: today ANY role can update ANY evidence item, A5
  gap). `get()`/`getVersion()`/`findByCid()` now take the caller and decrypt metadata for display with THEIR
  wrapped key, degrading to `metadataAvailable=false` (not an error) if they have none - the same shape as an
  IPFS-outage degrade. **`VerificationService`/`verify()` needed NO changes at all** - it already just re-fetches
  and re-hashes whatever bytes are at a CID, and those bytes are now ciphertext, so tamper-evidence keeps working
  for any caller, authorised or not.
- **New endpoint** `GET /api/evidence/{id}/file` (F2 Q3, owner-approved as new scope): streams the decrypted file
  to an authorised caller only (403 `KEY_NOT_AUTHORISED` otherwise); without it F2 would encrypt files into
  content nobody could ever legitimately retrieve again.
- **`CaseService` changes**: `addMember` re-wraps the ECK of every evidence item already linked to the case for
  the new member (F3's "re-wrap on access change"); `removeMember` deletes their wrapped copy for each one
  (revoke - not retroactive, a stated limit). `sync.EventProcessor` re-wraps for a custody transfer's receiver the
  moment `TRANSFER_INITIATED` is processed (F2/F3 Q2, owner-approved), the same async timing as H4's notification.
- **`DevUserSeeder`** provisions a keypair for every seeded user (new AND pre-existing, since `UserKeyService.
  provision` is idempotent) - the only user-creation path until A4 exists.
- **Flyway `V6__envelope_encryption.sql`**: `user_keys`, `evidence_content_keys`.
- **Verified live** (docs/TEST_CHECKLIST.md P5-F2F3): register -> IPFS holds ciphertext (88 bytes for a 60-byte
  upload, exactly the 28-byte IV+tag overhead) whose hash matches the ledger; the registrant decrypts, a
  non-member cannot (`metadataAvailable=false`, `/file` -> 403); adding the non-member as a case member grants
  them access live (`metadataAvailable` flips to `true`, `/file` now returns the exact original bytes);
  removing them revokes it again; a corrupted ciphertext byte on the real IPFS block still produces TAMPERED
  from `verify()`, which never touched a content key. First run against `memory-ledger` (the operator-held
  Fabric CA registrar credential needed to rebuild the per-user wallet, A2, was lost between sessions); the
  registrar and dev-user wallet were then rebuilt (owner-approved, DECISIONS D-060/D-061) and the two checks
  that need it - a real chaincode write, and the custody-transfer-receiver auto-wrap off G3's real Fabric event
  stream - both confirmed live (D-062). **All of F2/F3 is verified against real Fabric, real PostgreSQL and
  real IPFS; nothing remains verified only against a stand-in.**

## 19. As built (Phase 5, F5): upload consistency and retry, 2026-09-22

Extends D-024's pin-cleanup compensation (Phase 2, unchanged) with a bounded retry on a genuine Fabric-level
MVCC read conflict, scoped to `EvidenceService.register()`'s ledger write specifically. Full account:
docs/features/f5-upload-retry.md and DECISIONS D-063.

- **New `LedgerErrorCode.CONCURRENT_WRITE_CONFLICT`**, distinct from the chaincode's own `VERSION_CONFLICT` -
  `FabricErrors.translate`'s `mvccCommit` branch now returns it. Same HTTP 409 as `VERSION_CONFLICT`, so no
  existing caller's behaviour changes; it exists so retry logic can check a code instead of message text.
- **`EvidenceService.createEvidenceWithRetry`**: a plain Java loop (owner-approved, no new dependency - see
  D-063 for why `spring-retry` was rejected), 3 attempts with a fixed 200ms delay, mirroring `sync.
  EventSyncListener`'s tier-1 local retry exactly. Retries ONLY `CONCURRENT_WRITE_CONFLICT`; any other
  `LedgerException`, including `VERSION_CONFLICT`, fails on the first attempt. Sits entirely inside the existing
  pre-ledger try/compensate block, so `compensate()` still runs unchanged on final failure.
- **Deliberately not extended to `update`/status/transfer/disposal**: each of those read-modify-writes the SAME
  `recordKey(evidenceID)`, so a genuine MVCC conflict there is plausible, but retrying with the same
  `expectedVersion` cannot help (the chaincode's own version check would then correctly reject it) - a
  meaningful retry needs a re-read-rebuild step per call site, which is materially more than F5 asks for and was
  not built (KNOWN_GAPS).
- **Verified** with 6 focused unit tests against a Mockito-controlled `LedgerService` (the exact failure sequence
  under test control) and a live regression against real Fabric (registration succeeds with zero retry lines,
  confirming the wrapper is transparent for the normal case). A genuine MVCC conflict could not be triggered
  live for `CreateEvidence` specifically - it reads/writes no key any other transaction could contend for
  (confirmed by reading the chaincode) - documented as an accepted verification limit, not silently skipped.

## 20. As built (Phase 5, I1): chain-of-custody PDF report, 2026-09-22

Full account: docs/features/i1-chain-of-custody-report.md, DECISIONS D-064.

- **New `service/ReportService`**, deliberately dependent on ONLY `LedgerService` and `VerificationService` - no
  `ContentKeyService`/`IpfsClient`/`EvidenceMetadata` reference at all, confirming an owner design point before
  any code was written: the report never needs content decryption, so a Judge or Auditor never wrapped in for a
  given item's file/metadata (F2/F3) generates the exact same report as one who was. "Evidence details" is
  scoped to the ledger-native record only, excluding the off-chain metadata's free-text description (the content
  F2/F3 protects) - the same design line I3 already draws.
- **New endpoint** `GET /api/evidence/{id}/report`, same `Permissions.READ_EVIDENCE` as every other read, audited
  as a DOWNLOAD (A6).
- **New dependency: OpenPDF 2.2.2** (owner-approved, C-04), not iText (LGPL/MPL vs. AGPL/commercial-licensed).
- **Verified**: 3 unit tests reading the generated PDF back with OpenPDF's own `PdfTextExtractor` and asserting
  the ledger's real hashes/tx-ids/actors/actions appear verbatim (a genuine 5-step timeline against the real
  reference ledger, not a stub); a live run against real Fabric reproduced the same 5-step timeline through the
  API and confirmed every hash and transaction id in the real generated PDF matches the real `/history` response
  exactly, generated by a JUDGE confirmed to hold no content key for that item.

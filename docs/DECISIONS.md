# Decisions

<!-- Append-only. One paragraph max per entry: chose / rejected / why. -->

## D-001 — Build tool: Maven (2026-09-21)
Chose Maven with the wrapper (`mvnw`). Rejected Gradle. Nothing in the feature list requires either, and this was left as my call. Maven's declarative `pom.xml` plus the Spring Boot parent/BOM keeps dependency versions in one obvious place, which matters because every new dependency has to be reviewed and logged here; it is also what most Spring Boot documentation assumes, so examiners can read it without learning a DSL. Gradle would build faster and the Fabric sample Java apps use it, but neither outweighs readability for a final-year project. Neither tool is on PATH on this machine, so the wrapper is the bootstrap path either way.

## D-002 — Package structure: by layer, not by feature (2026-09-21)
Chose package-by-layer (`controller/service/repository/ledger/storage/security`), mirroring the layout in FEATURE_LIST.md. Rejected package-by-feature (`evidence/`, `custody/`, `auth/` each holding their own controller, service, and repository). By-feature keeps related code together and scales better in large codebases, but by-layer makes the two rules that matter most to this project mechanically obvious: only `ledger/` may know Fabric and only `storage/` may know IPFS. It also matches how the feature list names its classes, so IDs map straight to files.

## D-003 — Spring Boot 4.1.1 on Java 21 (2026-09-21)
Chose Spring Boot 4.1.1 (the current GA and Initializr default) with Java 21 (the installed JDK, LTS). Rejected 3.5.x, the original plan: start.spring.io no longer offers it, and hand-editing an older parent into the pom would start the project on a line that is at or past its open-source support window. Rejected 4.0.8: still offered, but its support window likely ends before the project does, and a Boot minor upgrade mid-project costs more than starting on the newer minor. Rejected Java 17 (Initializr's default) since JDK 21 is installed and nothing here needs 17. Known cost: Boot 4 renamed/split starters and moved Actuator classes, and defaults to Jackson 3, which must be reconciled with jjwt's Jackson 2 dependency (verified when A1 is built).

## D-004 — Phase 1 dependency set (2026-09-22)
Approved by the project owner (ARCHITECTURE.md section 7, Q7), recorded here with Boot 4 artifact names: `spring-boot-starter-webmvc` (REST and `RestClient`; rejected WebFlux, nothing here is reactive), `-validation` (K1), `-security` (A1/A3; rejected hand-rolled filters), `-data-jpa` (User entity, later H1 Specifications; rejected plain JDBC/jOOQ), `-actuator` (G4; rejected a custom health controller), `-flyway` plus `flyway-database-postgresql` (see D-006), `postgresql` runtime driver (rejected H2, which drifts from Postgres behaviour), `jjwt-*` (D-005), and Initializr's per-starter `*-test` starters. Deliberately not added: `fabric-gateway`, Testcontainers, WireMock, springdoc, Bucket4j, Spring Retry, all scheduled for later phases.

## D-005 — JWT library: jjwt 0.12.7 (2026-09-22)
Chose io.jsonwebtoken jjwt 0.12.7, the version line named in the approval and in FEATURE_LIST.md A1. Rejected `spring-security-oauth2-resource-server`: it is built around external identity providers and JWKS, which is heavier than one HS256 key needs. Not chosen: jjwt 0.13.0 exists on Central, but nothing requires it and the approval said 0.12.x; revisit at a routine upgrade. Boot 4 uses Jackson 3 (`tools.jackson`) while jjwt-jackson uses Jackson 2 (`com.fasterxml`, resolved to 2.21.5 by the Boot BOM); they use different packages and coexist, confirmed with `dependency:tree` and by the JWT tests and live run passing.

## D-006 — Database: PostgreSQL 16 with Flyway; Hibernate only validates (2026-09-22)
Chose PostgreSQL 16, schema owned by Flyway SQL migrations (`db/migration/V*.sql`), and `spring.jpa.hibernate.ddl-auto=validate`. Rejected `ddl-auto=update` (silent schema drift, weak audit story for an evidence system) and H2 for dev/test (behaviour differs from Postgres, for example `TIMESTAMPTZ`, UUID, `lower()` indexes). Table is `users`, not `user`, which is reserved in PostgreSQL.

## D-007 — Refresh tokens: server-side, hashed, rotating, reuse-detecting (2026-09-22)
Chose opaque random refresh tokens (256 bits), stored only as SHA-256 hash, rotated on every use, with an atomic `UPDATE ... WHERE revoked_at IS NULL` as the rotation gate, and revocation of all the user's tokens if a retired token is presented. Rejected stateless refresh JWTs: they cannot be revoked, so deactivating a user (A4) could not cut off refresh. Plain SHA-256 rather than BCrypt is deliberate: the token is pure randomness (nothing to brute-force) and lookup must be by hash. Verified live: 4 concurrent refreshes with one token yield exactly one 200, in 3 of 3 rounds.

## D-008 — Stubbed ledger reports health UNKNOWN, via a LedgerHealth type (2026-09-22)
Chose `LedgerService.health()` returning `LedgerHealth(UP|DOWN|UNKNOWN, detail)`, with the Fabric stub returning UNKNOWN. Rejected `boolean isReachable()`, which cannot express "not built yet", and rejected reporting DOWN, which would turn overall health red for something that is not broken. Spring's default aggregator ignores UNKNOWN while other components are UP (verified by `StatusAggregator.getDefault()` in a unit test); a real DOWN still wins.

## D-009 — Users before A4: dev-profile seeder only (2026-09-22)
Chose a `dev`-profile `ApplicationRunner` that creates one user per role, with the shared password taken from `BLOCKEVIDENCE_DEV_SEED_PASSWORD` (seeds nothing if unset). Rejected pulling a `POST /api/admin/users` endpoint into Phase 1: A4 is outside the phase. Rejected a hard-coded seed password or a Flyway data migration: both would put a credential in the repository (C-07).

## D-010 — JVM default timezone pinned to UTC in `main()` (2026-09-22)
Chose `TimeZone.setDefault(UTC)` before `SpringApplication.run`. Rejected requiring `-Duser.timezone=UTC` in every launch config (IntelliJ run configurations, Docker, CI would each need it, and forgetting one reproduces the crash) and a server-side `SET TIME ZONE` (the driver sends the client zone at connection startup, before any SQL runs). Details: `docs/bugs/jvm-timezone-postgres.md`. Side effect: application log timestamps are UTC.

## D-011 — Error handling: extend ResponseEntityExceptionHandler (2026-09-22)
Chose one `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`, overriding `handleExceptionInternal` so every Spring MVC exception also renders as ApiError; the machine code is the HTTP status name and the message is fixed text, never `ex.getMessage()`. Rejected a hand-listed set of `@ExceptionHandler`s (misses framework exceptions and leaves Spring's ProblemDetail format in places) and Spring's default error attributes. Explicit handlers for `AccessDeniedException`/`AuthenticationException` are required, otherwise the catch-all turns a `@PreAuthorize` denial into a 500.

## D-012 — No `@SpringBootTest` context test in Phase 1 (2026-09-22)
Removed Initializr's `contextLoads` test rather than keep it: it needs a live database and the required env vars, so it would fail on any machine without them. Rejected H2 (D-006) and pulling Testcontainers forward from L2. Coverage instead: unit tests, a `@WebMvcTest` slice with the real security filter chain, and the live run recorded in TEST_CHECKLIST.md. A full-context integration test with Testcontainers is due with L2 (Phase 5).

## D-013 — IPFS HTTP client: Spring `RestClient` (2026-09-22)
Chose `RestClient` with `SimpleClientHttpRequestFactory` and explicit connect/read timeouts (already in spring-web, no new dependency). Rejected the `java-ipfs-http-client` library (an extra dependency that, as far as I could tell, is no longer actively maintained; not re-verified) and Apache HttpClient/WebClient (extra weight for one endpoint). The timeout matters: without it a hung node would hang the health probe.

## D-014 — Stateless security decisions (2026-09-22)
Chose CSRF disabled (bearer-token header API with no cookies, so there is nothing for CSRF to protect), `STATELESS` sessions, default-deny (`anyRequest().authenticated()`), and `JwtAuthenticationFilter` built with `new` inside `SecurityConfig` rather than as a bean (a Filter bean is auto-registered a second time as a plain servlet filter). Rejected form login/HTTP Basic (disabled). Known limitation recorded in ARCHITECTURE.md section 10: an access token outlives deactivation by up to its 15-minute TTL.

## D-015 — Test signing keys generated at runtime (2026-09-22)
Chose `TestSecrets` generating random keys per test run, so no key literal exists in the repository. Rejected fixed fake keys in test sources: harmless in practice, but constraint C-07 says no secrets in code and a literal invites the question every time a secret scanner runs.


## D-016 — FINAL LedgerService and IpfsClient signatures, and why they changed from the Phase 1 stubs (2026-09-22)
**LedgerService (final).** Phase 1 sketched `createEvidence(String evidenceId, String caseId, String cid, String sha256, String actorId)`, `updateEvidence(id, cid, sha, reason, actor)`, `updateStatus(id, String status, ...)`, `initiateTransfer`, `acceptTransfer`, `Optional<..> getEvidence`, `List<..> getHistory`, `health()`. Final: `createEvidence(LedgerNewEvidence, LedgerActor)`, `updateEvidence(id, int expectedVersion, newMetadataCid, newMetadataSha256, reason, LedgerActor)`, `updateStatus(id, expectedVersion, EvidenceStatus, reason, actor)`, `requestDisposal / approveDisposal / rejectDisposal(id, expectedVersion, reason-or-note, actor)`, `getEvidence(id): Optional<LedgerEvidenceRecord>`, `getHistory(id): List<LedgerHistoryEntry>`, `findEvidenceIdsByCid(cid): List<String>`, `health()`. Why: (1) one record carries both the file and the metadata artifact (CID **and** SHA-256 each), because C1 puts the hash on the ledger next to the CID and C2 needs both to verify; a lone `cid` string could not express that. (2) `expectedVersion` on every write, since two officers editing the same item must not silently overwrite each other (Fabric's own MVCC check only catches same-block collisions, not stale reads). (3) `LedgerActor` bundles user id and role, both needed by the chaincode's role check (A3). (4) `EvidenceStatus` replaces a bare String so illegal values cannot compile. (5) Three disposal operations added for B5; approval needs its own role and must pin the version reviewed. (6) `findEvidenceIdsByCid` added because B3 needs lookup by CID and the peer's LevelDB has no rich queries. (7) `getHistory` returns the **full record per version**, so an old version can be shown without extra calls (B4). (8) **`initiateTransfer` and `acceptTransfer` were removed**: their shape belongs to D2 (two-step custody, Phase 3) and guessing it now would just have to be undone. Interface additions are cheap; a wrong guess is not. (9) Errors are a closed set (`LedgerErrorCode`, D-017) instead of free text. Delete is deliberately absent (C-02) and a test asserts no method name contains delete/remove/purge.
**IpfsClient (final).** Phase 1: `String pin(InputStream, String)`, `InputStream fetch(String)`, `void unpin(String)`, `boolean isReachable()`. Final: `pin` unchanged (returns only the CID; the caller computes hash and byte count while streaming, since Kubo's reported `Size` includes DAG overhead), **`fetch` replaced by `<T> T read(String cid, ContentReader<T>)`** (a returned InputStream can be leaked open; a callback lets the client always close the connection), `unpin` is now idempotent (Kubo answers 500 "not pinned" the second time), and failures are split into `ContentNotFoundException` versus `StorageUnavailableException`, because C2 must not report an unreachable node as "content missing".

## D-017 — Ledger errors are a closed set; exceptions carry their own HTTP status (2026-09-22)
Chose `LedgerErrorCode` = `EVIDENCE_NOT_FOUND, EVIDENCE_EXISTS, FORBIDDEN_ROLE, INVALID_ARGUMENT, INVALID_STATE, VERSION_CONFLICT, LEDGER_UNAVAILABLE`, each mapped to one HTTP status (404, 409, 403, 400, 409, 409, 503), and an `exception/ApiException(status, code, message)` base that `GlobalExceptionHandler` maps generically. The first six are exactly what the chaincode returns as `CODE: message`, so the Fabric implementation can translate them without string guessing. Rejected: parsing free-text ledger messages (brittle) and putting ledger types in `exception/` (would break the rule that `exception/` does not import `ledger/`).

## D-018 — In-memory reference ledger behind a `memory-ledger` profile (2026-09-22)
Chose `InMemoryLedgerService`, active only under `SPRING_PROFILES_ACTIVE=...,memory-ledger`, with `FabricLedgerService` `@Profile("!memory-ledger")`. Reason: the chaincode design is awaiting approval, yet register/verify/update/dispose need something to run against, and it doubles as an executable spec (14 tests) that the Go chaincode tests will mirror. Rejected: Mockito-only tests (cannot drive a live run) and waiting for the chaincode (would leave Phase 2's Spring side unverified). Risk accepted and contained: it logs a WARN at startup, reports itself in `/actuator/health` as "NOT a blockchain, NOT tamper-proof", and the checklist labels every live result as run against it. The role table now exists in three places (`Permissions`, `InMemoryLedgerService.CAN_*`, the chaincode); comments point at each other.

## D-019 — Evidence permission matrix (PROPOSED, awaiting owner confirmation G3) (2026-09-22)
Chose: register and update metadata = COLLECTOR, FORENSIC_ANALYST; status change = COLLECTOR, FORENSIC_ANALYST, PROSECUTOR (ledger only in Phase 2); request disposal = COLLECTOR, PROSECUTOR; approve/reject disposal = JUDGE only; read = any authenticated role. ADMIN and AUDITOR hold no write permission (A4: an Admin cannot edit evidence). Rejected: letting ADMIN approve disposal (an administrator is not a judicial authority) and one wide "write" role. Read access is unrestricted by case until A5 (not scheduled); any logged-in user can read any evidence.

## D-020 — Kubo runs OFFLINE in development; public IPFS exposure is a real risk (2026-09-22)
Found by probing, not assumed: a default `ipfs/kubo` container joined the public IPFS network and fetched 118 KB from strangers for an arbitrary CID, so anything pinned on such a node is retrievable by anyone who learns its CID, and CIDs are stored on the ledger. Chose `docker run ... ipfs/kubo daemon --offline` for development: no peers, no DHT announcements, and a missing CID fails in ~0.15 s instead of hanging. Not solved: production still needs a private swarm (swarm key, no bootstrap peers) or, better, envelope encryption (F2, currently Stretch). Until then no sensitive content should be registered on a networked node, and metadata documents are equally readable. Rejected: relying on "the CID is hard to guess" (CIDs are recorded on the ledger and in API responses).

## D-021 — File fields are immutable; updates change metadata only; base metadata is verified first (2026-09-22)
Chose: after creation `fileCid/fileSha256/fileSize` never change (enforced in the ledger, not only Spring); B4 replaces only the metadata pointer, each version being a NEW IPFS document that names `previousMetadataCid`. Before building version n+1, the service hash-checks the current metadata against the ledger and refuses on mismatch (409 INTEGRITY_CHECK_FAILED), otherwise an update would launder tampered metadata into a fresh, ledger-blessed version. Rejected: allowing file replacement (a changed file is a different evidence item) and updating metadata in place.

## D-022 — Verification semantics (2026-09-22)
Chose: overall TAMPERED beats NOT_FOUND beats VERIFIED (a proven mismatch is more serious than missing content); an **unreachable** node is HTTP 503, never NOT_FOUND (not being able to look is not evidence of absence); an unknown evidence id is HTTP 404 like every other endpoint, while NOT_FOUND is reserved for "the ledger has it but the content is gone". `GET /api/evidence/{id}` reports `verification.status = NOT_CHECKED` unless `?verify=true`, because verifying re-downloads and re-hashes the whole file. Rejected: always verifying on GET (a 50 MB download per page view) and mapping IPFS outages to NOT_FOUND. This differs slightly from "GET returns current verification status" in FEATURE_LIST.md B3; flagged for the owner.

## D-023 — Lookup by CID is its own route returning a list (2026-09-22)
Chose `GET /api/evidence/by-cid/{cid}` returning a list (404 if empty) over overloading `GET /api/evidence/{id}` with either kind of value. One CID can belong to several evidence items (the same file registered twice), so a single-object answer would be ambiguous, and an id/CID guess by prefix is fragile. The PDF's "keep support for lookup by CID" is met by this route.

## D-024 — F5 compensation only, with a safety rule; retry stays in Phase 5 (2026-09-22)
Register hashes+pins the file, pins the metadata, then writes the ledger last. If the ledger write fails the pins are removed **only if the ledger confirms no evidence references that CID**; if the ledger cannot be asked, nothing is unpinned. Reason: the same file registered twice shares one CID, so blind unpinning could delete content another record depends on; an orphaned pin costs disk, a wrongly removed one loses evidence. Spring Retry on MVCC conflicts (rest of F5) is not built; flagged as slightly outside the Phase 2 list because the compensation step is part of making B1 correct.

## D-025 — IPFS upload is built from plain Spring types, not MultipartBodyBuilder (2026-09-22)
Chose `LinkedMultiValueMap` with an `HttpEntity<InputStreamResource>` part. Found by a failing test: `MultipartBodyBuilder` references `org.reactivestreams.Publisher`, absent from this servlet-only classpath, so `pin` died with `NoClassDefFoundError` at runtime. Rejected: adding reactive-streams just for a helper class (C-04). `InputStreamResource` is used un-subclassed on purpose, because Spring only avoids reading the whole stream to learn its length when the class is exactly `InputStreamResource`.

## D-026 — Upload policy (2026-09-22)
Chose a hard size limit via `spring.servlet.multipart.max-file-size` (default 50 MB, env `UPLOAD_MAX_FILE_SIZE`; a 60 MB upload answers 413) and a type allow-list on the **client-declared** content type (images, PDF, text, audio, video, zip). `application/octet-stream` is excluded by default so the list means something; add it in configuration for forensic disk images. Rejected: content sniffing with Apache Tika (new dependency, C-04). Stated limitation: the declared type is not verified against the bytes, so this is a guard against mistakes, not a security boundary.

## D-027 — Disposal design (2026-09-22)
Chose a request/approve/reject flow (COLLECTOR or PROSECUTOR requests, JUDGE decides, reason mandatory), approval pinned to the version the judge reviewed, DISPOSED freezes the record but leaves it readable and verifiable, and no IPFS unpin ever happens (an irreversible action nobody asked for). Disposal may be requested from any non-DISPOSED status; the judge is the safeguard (tighten with D1). Rejected: DELETE endpoints (C-02) and physically purging the file.

## D-028 — New `domain/` package for shared types (2026-09-22)
Chose `domain/` (`EvidenceStatus`, `EvidenceType`, `VerificationStatus`, `Cid`, `EvidenceMetadata`) for types used by DTOs, services and the ledger interface. Rejected putting them in `model/` (JPA entities, and DTOs must not import entities) or in `ledger/` (DTOs and controllers must not import `ledger/`). `domain/` imports nothing from the other application packages.

## D-029 — The collector field is not bound at all (C-05) (2026-09-22)
Chose a `RegisterEvidenceRequest` with no collector/officer component, so a client-supplied `collector`, `collectorId` or `createdBy` in the JSON is ignored by deserialisation and the acting user always comes from the JWT. Live-verified: a request carrying `"collector":"forged@evil.example"` and a zero-UUID `collectorId` produced a record whose `createdBy` and `metadata.collectorId` equal the token user's id. Rejected: binding the field and overwriting it (leaves a trap for the next developer) and rejecting the request (a client that sends it is confused, not hostile, and the outcome is safe either way).


## D-030 — Chaincode language, framework and dependencies (approved G1, G6) (2026-09-22)
Chose Go with `fabric-contract-api-go` v1.2.2 (pulls `fabric-chaincode-go`, `fabric-protos-go`; resolved by `go mod tidy`, versions pinned in `chaincode/evidence/go.sum`) for a NEW chaincode named `evidence`; on the Java side `org.hyperledger.fabric:fabric-gateway` 1.12.1 and `io.grpc:grpc-netty-shaded` 1.83.1 (runtime). Rejected: JavaScript/TypeScript and Java chaincode, and the raw shim (design section 2); reusing the name `basic` (its old definition stays committed and its semantics differ). The gRPC transport is pinned to the exact version `fabric-gateway` was built against (1.83.1, read from its POM) rather than the newest 1.84.0, to avoid an untested combination. Also approved as part of G6 and logged here: the Go modules above (C-04). `vendor/` is git-ignored and regenerated by `go mod vendor` at deploy time.

## D-031 — Chaincode signature and encoding choices (2026-09-22)
All chaincode arguments are strings, including `expectedVersion` and `fileSize`, which the chaincode parses itself. Reason: the framework's own parse errors would not carry our error codes; parsing in our code keeps every failure a `CODE: message`. Timestamps are RFC 3339 strings taken from the transaction, not `time.Time`, to keep generated metadata simple and the encoding explicit. Every write returns a `TxResult{txId, timestamp, version}` so the backend learns the ledger's own transaction id and time without a second query. `GetHistory` sorts by record version instead of trusting the peer's iteration order (a test simulates a newest-first peer). No function accepts or performs a delete; a test inspects the syntax tree for `DelState`/`PurgePrivateData`/`DelPrivateData` calls and lists the exported functions.

## D-032 — Deployed as v1.1 sequence 2, because v1.0 had a defect only a real peer could show (2026-09-22)
`evidence` v1.0 seq 1 was deployed first. Its first live query failed with `Value did not match schema: return.disposal: reason is required...`: the contract framework validates every return value against a generated schema in which ALL struct fields are required unless tagged `metadata:",optional"`, so any physical item (no file) or item without a pending disposal was unreadable. The 21 unit tests with a fake stub could not see it. Fix: tag the six `omitempty` fields optional, add a reflection test that ties `omitempty` to `optional`, and redeploy as v1.1 seq 2 (a committed definition cannot be edited). v1.0 records written during testing remain on the ledger and read correctly under v1.1.

## D-033 — FabricLedgerService: lazy connection, closed error set, no startup dependency (2026-09-22)
Chose a lazily created gateway connection, so the application starts without Fabric; a missing identity configuration is `503 LEDGER_UNAVAILABLE` on the call and UNKNOWN in health, an unreachable network is `LEDGER_UNAVAILABLE`/DOWN, neither is a startup failure. The identity is referenced by file path from environment variables (C-07); each path may be a file or a directory holding one file. Errors are recognised by matching the closed set of chaincode codes in the gateway's message text (`FabricErrors`, pure and unit-tested with real peer messages), an MVCC commit failure becomes `VERSION_CONFLICT`, connectivity failures `LEDGER_UNAVAILABLE`, and anything unrecognised is a generic `LEDGER_UNAVAILABLE` with no detail leaked (no paths, no stack). Health does a real `GetEvidence` for an id that cannot exist and treats `EVIDENCE_NOT_FOUND` as healthy, because that answer came from the chaincode itself. Rejected: connecting in the constructor (Fabric down would prevent the whole app, including login, from starting) and a dedicated chaincode Ping function (a design change for no gain).

## D-034 — Gateway error fragments are joined with newlines (found live) (2026-09-22)
The first full run through real Fabric was identical to the reference run except one message: `...can no longer change ABORTED: failed to endorse transaction, see attached details...`. The gateway reports the chaincode message and its own text as separate fragments; joining them with spaces let the "read to end of message" pattern run into the next fragment. Fragments are now joined with newlines and the pattern stops at end of line; a test uses the real multi-fragment shape.

## D-035 — Real chaincode output is a test fixture; the wire format is pinned against it (2026-09-22)
`src/test/resources/fabric/*.json` are captured from the deployed chaincode (`chaincode/scripts/capture_wire.sh`): a real transaction result, a physical record (no file or disposal fields), a disposed digital record, a five-version history, CID lookups, and two real peer error messages. `FabricLedgerServiceTest` parses them with a NON-lenient mapper, so a renamed Go json tag or a shape the Java records cannot read fails a Java test. Rejected: hand-written JSON samples (they would encode my assumptions, not the peer's behaviour).

## D-036 — Revive the existing Fabric network, do not recreate it (approved G7) (2026-09-22)
The network's containers and volumes had been stopped for four months; they started cleanly, the channel was intact (height 48) and certificates were still valid, so nothing was recreated and `network.sh down` was never run. Corrects the design document's claim that the toolchain and old chaincode source were "not on disk": they are in WSL (`docs/FABRIC_RUNBOOK.md` section 1). Deployment and verification scripts are stored in `chaincode/scripts/` and `scripts/live/` with machine paths parameterised, because a deployment that exists only in a scratch folder is not reproducible.

## D-037 — Orphaned pins are possible during a ledger outage (accepted, by design) (2026-09-22)
When the ledger is down, register fails with 503 after IPFS content has been pinned, and the compensation step (D-024) cannot ask the ledger whether those CIDs are referenced, so it deliberately unpins nothing. Result: unreferenced pins stay on the IPFS node until cleaned up. This was observed live (register during a peer outage answered 503). Accepted because a leaked pin costs disk while a wrongly removed pin could destroy evidence. A future sweep can reconcile pins against the ledger (not built).

## D-038 — Custody moves only on acceptance; the pending index is never deleted (2026-09-22)
A transfer is two ledger transactions by two different users: `InitiateTransfer` (current custodian) records `transfer.state = PENDING` and leaves `currentCustodian` untouched; `AcceptTransfer` (the named receiver, matched on user id AND role) is the only step that changes custody. Reject (receiver) and cancel (sender) close it with custody unchanged. One pending transfer per item; none during a pending disposal or after DISPOSED. The "transfers to me" list uses composite keys `TRF~<userId>~<evidenceId>` written at initiation; they are never deleted (C-02, no `DelState` anywhere) and are filtered on read against the record's current state. Rejected: a one-step "give custody" (the sender could push evidence onto anyone), and deleting the index entry on resolution (would need DelState).

## D-039 — Chaincode v1.2 sequence 3, additive; old records are normalised on read (2026-09-22)
Custody added functions and a `transfer` field to the record, so it shipped as a NEW chaincode version (v1.2, sequence 3), never an edit of the committed v1.1 definition (a committed definition cannot be edited). Records written by v1.1 have no `transfer` key; `load()` and `GetHistory` fill `transfer = {state: NONE}` on read, so no data migration and no rewrite of history was needed. The Java record does the same for an absent key (`docs/bugs/ledger-record-without-transfer-key.md`), so neither side depends on the other. Side effect: a mistaken first deploy (without `VER`/`SEQ`) left an unused installed package `evidence_1.0` on both peers, harmless and listed in ROLLBACK.

## D-040 — Status machine finalised as a strict line, checked twice (2026-09-22)
`COLLECTED -> PROCESSING -> ANALYZED -> ARCHIVED -> RELEASED`; no backward move, no skipping; DISPOSED only through an approved disposal (B5). The rule lives in `EvidenceStatus.canMoveTo` (Java) and `model.go` (chaincode), each with tests; the service checks first for a clear 409 without spending a transaction, the chaincode enforces it again for callers that bypass Spring. Roles stay COLLECTOR, FORENSIC_ANALYST, PROSECUTOR (D-019). Open for the owner: any of those roles may move any item, not only its custodian (KNOWN_GAPS D).

## D-041 — Cases live in PostgreSQL only; the ledger keeps the case number as a string (2026-09-22)
Tables `cases` and `case_members` (Flyway V2, a new migration), consistent with C-06 (no descriptive/personal data on the ledger). The case number uses the ledger's `caseId` character set, so any case number is a valid `caseId`. Membership carries a role ON THE CASE, separate from the system role. There is no endpoint that deletes a case. Only ADMIN and PROSECUTOR manage cases (D-019).

## D-042 — Lead-officer change is an in-place role change (found live) (2026-09-22)
The first implementation deleted and re-inserted the previous lead's membership row and failed against real PostgreSQL (Hibernate flushes inserts before deletes, unique `(case_id, user_id)`). Now the existing rows change role (`CaseMember.changeRole`). The only remaining `delete` in the code base is removing a non-lead member row (not evidence, so C-02 does not apply). See `docs/bugs/case-lead-change-unique-violation.md`.

## D-043 — The custody timeline is derived from ledger history, not stored (2026-09-22)
`GET /api/evidence/{id}/chain-of-custody` maps the ledger's history entries to events; each carries its own ledger txId and timestamp. Nothing is copied into PostgreSQL, so the timeline cannot diverge from the ledger. Rejected: a `custody_events` table (a second copy that could disagree, and mutable).

## D-044 — E3 and A2 held for the owner; Phase 3 test data uses `FAB-P3-*` (2026-09-22)
E3 (evidence-to-case linking) needs a change to Phase 2's register path or to `CreateEvidence`, so it was NOT built; two options are laid out in `docs/features/e1-e2-cases-and-officers.md`. A2 (per-user Fabric identities) changes how every write is authenticated; per the owner's instruction only its design (`docs/A2_IDENTITY_DESIGN.md`, questions A2-Q1..Q8) was written, after an empirical spike against the real CA. Nothing that authenticates B1-B5 changed. Ledger records created by Phase 3 checks use case ids `FAB-P3-*` (permanent, KNOWN_GAPS C).

## D-045 — Wire fixtures: keep the v1.1 ones, add v1.2 ones (2026-09-22)
`capture_wire_p3.sh` writes `p3-*.json/txt` (pending and accepted transfers, a never-transferred record, a five-step history, pending lists, two real error texts). The Phase 2 fixtures (captured on v1.1, no `transfer` key) are kept on purpose: they are the real "record written before transfers existed" shape.

## D-046 — E3: Option B (Spring link table), case validation enforced unconditionally (2026-09-22)
Two designs were offered (a chaincode composite-key index vs a PostgreSQL link table); the owner chose the PostgreSQL table
(`case_evidence`, a new Flyway migration), explicitly to keep case validation in PostgreSQL and to avoid another chaincode
version for this. `EvidenceService.register` now requires the caseId to name an existing case (case-insensitive), checked
before any IPFS work; this is enforced unconditionally, not behind a flag, because the feature's own definition ("evidence
belongs to a real case entity") and the owner's phrasing both point at enforcement rather than an optional check. All three
live scripts were updated to create their cases first, since this is a real, visible change to how `register` behaves.

## D-047 — A2: certificate-authoritative authorise(), argument kept for compatibility, hard cutover (2026-09-22)
Approved as designed (A2-Q1..Q8): `authorise()` now reads `role`/`hf.EnrollmentID` from the caller's certificate and
requires the `actorId`/`actorRole` ARGUMENTS to equal it - every chaincode function signature, the Java `LedgerService`
interface and every Phase 2/3 controller/service are unchanged. No fallback for a certificate with no `role` attribute
(strict): reads still work (they never call `authorise`), writes are refused. This is a hard cutover, so enrollment of
every user had to happen BEFORE deploying the enforcing chaincode version (`docs/FABRIC_RUNBOOK.md` section 8), verified
live before the deploy (a per-user write against the still-non-enforcing v1.2 succeeded and showed the right creator
certificate) and again after (`evidence` v1.3 seq 4). Rejected: a soft cutover accepting either the argument alone or a
certificate (keeps the C-08 hole open, since a bug could still forge the argument for an unenrolled or wrong identity).

## D-048 — Java side: per-user Gateway for writes, service identity kept for reads, one shared gRPC channel (2026-09-22)
`FabricLedgerService` now has two families of connection: the existing SERVICE identity (`FABRIC_CERT_PATH`/`FABRIC_KEY_PATH`,
unchanged) for every read and the health probe, and a small bounded LRU cache (64 entries) of per-user `Gateway`/`Contract`
pairs for writes, built from `IdentityStore.find(actor.userId())`. All of them share ONE `ManagedChannel` (transport only;
a `Gateway` is a lightweight wrapper with its own signing identity over that channel, not a new TCP/TLS connection), so
per-user signing does not multiply network connections. A user with no wallet entry gets `403 LEDGER_IDENTITY_MISSING`
(new `LedgerErrorCode`) before any chaincode call - never a silent fallback to the service identity, which would reopen
C-08. Rejected: a Gateway per REQUEST (works, but discards a cheap reuse opportunity for a user who writes repeatedly);
an unbounded cache (a long-running backend with many enrolled users would accumulate Gateways forever).

## D-049 — Wallet: files on disk, one directory per user, optional PKCS#8 encryption via BouncyCastle (2026-09-22)
`IdentityStore`/`FileWalletIdentityStore` (Option 1 in the design, an operator script, approved A2-Q2): `<FABRIC_WALLET_DIR>/<userId>/{cert.pem,key.pem}`,
maintained by `scripts/fabric/enroll_users.sh`, never written by the running app. An unencrypted key needs no
passphrase; an "ENCRYPTED PRIVATE KEY" (PKCS#8) key is decrypted with `FABRIC_WALLET_PASSPHRASE` using `bcpkix-jdk18on`
(already transitively present via `fabric-gateway`; declared explicitly per C-04/A2-Q8). Found live: decrypting a
PBES2-encrypted key needs the BouncyCastle JCE **provider** registered (`Security.addProvider`), not just its classes on
the classpath - the unit test's own static initialiser masked this until the real application was run
(`docs/bugs/wallet-key-decrypt-needs-bc-provider.md`). A `wallet` health indicator warns (DOWN) when any enrolled
identity is within 30 days of its certificate's expiry.

## D-050 — Registrar: least-privilege `be-registrar`, a one-time operator script, never held by the app (2026-09-22)
`scripts/fabric/bootstrap_registrar.sh` registers `be-registrar` (may register only `client` identities carrying only the
`role` attribute, per the spike's proven escalation refusals). Its secret lives only in the operator's shell
(`BE_REGISTRAR_SECRET`), passed to `scripts/fabric/enroll_users.sh` when enrolling users; the running backend never sees
either secret. Reused the exact registrar shape already validated in the A2 spike (TEST_CHECKLIST Appendix P3-A) rather
than re-deriving it.

## D-051 — G3: two retry tiers, a real DB-backed checkpoint, evidence_activity does double duty (2026-09-22)
Approved as designed (docs/G3_SYNC_DESIGN.md). Tier 1 (up to 3 attempts, fixed 200ms) retries only the DATABASE
transaction for a Postgres blip, leaving the Fabric event stream open; tier 2 (exponential backoff, base 1s,
factor 2, capped 30s, +/-20% jitter) tears down and reconnects the stream for anything else, including tier 1
exhausted. Chosen over one uniform "any failure reconnects" policy: reopening a chaincode event subscription has
real cost, so a sub-second Postgres hiccup should not pay for it. The checkpoint is a Postgres row, updated in the
SAME transaction as the read-model write it protects (not the Fabric SDK's own FileCheckpointer/InMemoryCheckpointer,
neither of which can be atomic with a Postgres write). `evidence_activity` is both the idempotency log (UNIQUE
tx_id) and the H3 feed source - one table, not two, since every processed event is already a feed-worthy row.

## D-052 — G3 health: a NEW `eventSync` actuator component, DOWN only after 5 consecutive stream-level failures (2026-09-22)
UNKNOWN before the first connection (matches LedgerHealthIndicator's honesty), UP once connected with fewer than 5
consecutive tier-2 failures since, DOWN at 5 (by then backoff has reached or is near its 30s cap - roughly a
minute of sustained failure, not a transient blip). DOWN is an observability signal only: the loop never stops
retrying underneath it (design section 6). Rejected: counting tier-1 (local DB) retries toward this threshold -
they are invisible to it on purpose, since they represent a different failure mode (design section 9).

## D-053 — G3 first-ever run replays the whole ledger from block 0 (2026-09-22)
An empty checkpoint (block_number IS NULL) means "start from block 0", not "start from now": the read model must
reflect ALL existing evidence on first startup, not only future writes, given the ledger already holds real data
from every prior phase's live verification. Verified live: the first run against the real chain backfilled 164
activity rows / 90 projection rows in one pass (TEST_CHECKLIST P4-G3).

## D-054 — H1-H3 read only from the G3 projection; H1's free text matches CURRENT state, not history (2026-09-22)
`SearchService`/`DashboardService`/`ActivityService` never call `LedgerService` (the whole point of G3): they query
`evidence_projection`/`evidence_activity` via Spring Data JPA Specifications and native aggregate queries. Found
live: H1's `q` filter matches `evidence_projection.last_reason`, which is the CURRENT reason only - a search for
a word from an EARLIER version's reason (superseded by a later status change/disposal) correctly returns nothing,
since the projection is a current-state table by design (D-051). Full-text search over history, if ever wanted,
belongs against `evidence_activity` (H3), not H1; not built, since nothing asked for it.

## D-055 — H4: notifications are written inside G3's own transaction; a tamper alert is not a ledger event (2026-09-22)
`NotificationService.notifyForEvent` is called from `EventProcessor.apply` in the SAME transaction as the
activity/projection/checkpoint write (design section 5): a rollback there discards the notification too, so there
is no window where a notification exists for an event that was not actually committed. It never throws outward
(a lookup failure, e.g. a caseId naming no real case, is logged and skipped) so a notification problem can never
break evidence sync. `TAMPER_ALERT` is raised directly by `EvidenceService` the moment a verify call finds
TAMPERED, independent of G3 entirely (chaincode events carry no such thing). TRANSFER_INITIATED notifies only the
named receiver; STATUS_CHANGED/DISPOSAL_* notify every case member (via `CaseFileRepository`/`CaseMemberRepository`,
looked up by the ledger's caseId string, same pattern as E3). Rejected: notifying on every action (CREATED,
METADATA_UPDATED, TRANSFER_ACCEPTED/REJECTED/CANCELLED) - kept to the actions the design named, to avoid noise.

## D-056 — A6: instrumented at 4 points, not one central filter; VIEW vs DOWNLOAD is the verify flag (2026-09-22)
No AOP: the codebase has no existing AOP precedent, and only 2 controller methods need VIEW/DOWNLOAD
instrumentation, so `AuditService.recordAccess` is called directly from `EvidenceController.get`/`verify` -
simpler and more debuggable than a new aspect. `verify=true` (or the dedicated `/verify` endpoint, which always
re-hashes) is logged as DOWNLOAD, since it is the one point the server actually re-fetches file bytes from IPFS;
a plain GET is VIEW. Failed attempts are caught at 3 existing error-handling points
(`ApiAuthenticationEntryPoint`, `ApiAccessDeniedHandler`, `GlobalExceptionHandler.handleAccessDenied`/
`handleAuthenticationFailed`), not a new one, since those already own the definitive "this request failed"
decision. `AuditService.currentIp()` uses `RequestContextHolder` (works inside MVC, e.g. the controller and
`GlobalExceptionHandler`) but the two security-filter-chain handlers (`ApiAuthenticationEntryPoint`/
`ApiAccessDeniedHandler`, which run BEFORE `DispatcherServlet`) pass the IP explicitly instead, since
`RequestContextHolder` is not reliably populated that early.

## D-057 — A6/Phase 1 pairing: TOKEN_USED_AFTER_DEACTIVATION checks user.enabled at audit-write time, not auth time (2026-09-22)
Per the owner's instruction: `recordAccess` looks up the CURRENT `user.enabled` value (a fresh `UserRepository`
read, not the JWT's claims, which say nothing about deactivation) every time it logs a VIEW/DOWNLOAD, and records
`TOKEN_USED_AFTER_DEACTIVATION` instead when the user has since been disabled. This does not change what the
request does (still 200; the access-token TTL gap from Phase 1 is not being fixed here) - only what gets logged.
Verified live end to end (not just the schema, per the owner's explicit instruction): a still-valid token's
request after its owner was deactivated produced a `TOKEN_USED_AFTER_DEACTIVATION` row, distinguishable in the
same query from the ordinary `VIEW`/`DOWNLOAD` rows the SAME user's earlier, pre-deactivation requests produced
(TEST_CHECKLIST P4-A6). No A4 (admin user management) endpoint exists yet to deactivate a user through the API,
so the live check used a direct SQL `UPDATE users SET enabled=false` - exactly what A4 would eventually do,
without building A4 itself (out of scope for Phase 4).

## D-058 — F4 audit: no C-06 violation found; free-text ledger fields accepted as a documented residual, not fixed (2026-09-22)
Audited every path that writes to the ledger (chaincode structs, `LedgerActor`/`LedgerNewEvidence`, every
`FabricLedgerService.submit` call site) against C-06. Result: every automatically-written field is an opaque
id, hash, CID, role name, enum or timestamp - no email/fullName/department ever reaches the ledger (confirmed
by grep, zero matches outside auth/audit code). The one thing that DOES reach the ledger as free text is the
`reason`/`note`/`notes` fields on status change, disposal and transfer, validated for length only
(`@Size(max=1000)`), never content - a user could type personal data into one and it would be permanent.
Rejected: adding content-scanning/PII-redaction to these fields - well outside this project's scope, and not
requested; these fields exist for a legitimate, required purpose (B5/D2's mandatory reason). Documented instead
as an accepted known gap (KNOWN_GAPS.md section B, docs/features/f4-personal-data-off-chain-audit.md). This
audit is also treated as F4's completion per the owner's framing ("verification pass, not new code, unless it
finds a violation") - no violation was found, so no code changes accompany this entry. It doubles as the answer
to whether F2/F3 solve a real problem: yes, but the real exposure is IPFS file/metadata CONTENT (C-09), not the
ledger, which is clean by design.

## D-059 — F2/F3 built per the approved design; live-verified against memory-ledger, not real Fabric (2026-09-22)
Built exactly per docs/F2_F3_ENVELOPE_ENCRYPTION_DESIGN.md (owner-approved Q1-Q4, C-10 added first). One
implementation choice not spelled out in the design: EvidenceService.streamFile (the new /file endpoint, Q3)
reuses readVerifiedMetadata to learn the plaintext filename/content-type - this gives the download path the
SAME ledger-hash integrity check update() already relies on, for free, rather than adding a second, weaker
metadata read. Rejected: a separate "light" metadata read for just the FileInfo - would have skipped the hash
check that catches a swapped/corrupted metadata document before ever streaming a (still-valid) file back.

**Live verification used the memory-ledger profile, not real Fabric, and this needs to be closed out.** The
operator-held Fabric CA registrar credential (be-registrar on ca_org1, A2) was needed to rebuild the per-user
wallet (lost between sessions - the wallet directory lived outside the repo and was not persisted). Recovering
it requires reissuing the registrar's secret (fabric-ca-client identity modify be-registrar --id.secret ... as
the CA bootstrap admin), which this session's own permission policy classified as a secret-store write and
refused, independent of the owner's instructions. Real Postgres and real IPFS were used throughout (docker cp
plus cmp located the exact on-disk block backing a CID - flatfs's key is a base32 of the raw multihash, not the
CID string, so this replaced Phase 2's old "grep for a plaintext marker" corruption technique, which cannot
work once content is encrypted); only the ledger is a stand-in. Register/get/update's key-wrapping logic,
addMember/removeMember's re-wrap/revoke, the download endpoint, and the tamper-detection property were all
proven against this real Postgres+IPFS stack. Not yet proven against real Fabric: a write actually reaching the
chaincode with a per-user identity, and the custody-transfer-receiver auto-wrap path (it is wired into
sync.EventProcessor, which only runs against FabricLedgerService's real chaincode event stream -
InMemoryLedgerService implements no LedgerEventSource, so G3 never runs under memory-ledger and this path could
not be exercised live this session). Follow-up: once the registrar secret is restored (owner action, or an
explicit approval for this session to do it), re-run scripts/fabric/enroll_users.sh and repeat the F2/F3 live
check against the real network, specifically the transfer-receiver wrap.

## D-060 — be-registrar's secret reissued via the CA bootstrap admin (2026-09-22)
The registrar credential chosen at A2 time (docs/A2_IDENTITY_DESIGN.md) was lost between sessions (held only in
an operator shell, never written down, per C-07) - the wallet it would rebuild was gone too. Owner-approved
recovery, no new access created: enrolled the CA bootstrap admin (its secret read back from the running
ca_org1 container's own start command via `docker inspect`, exactly as scripts/fabric/bootstrap_registrar.sh
already does for a first-time setup - a read of something the operator's host access already exposes, not a
new secret), then ran `fabric-ca-client identity modify be-registrar --secret <new>` as that admin (note: the
flag is `--secret`, NOT `--id.secret` - `identity modify` takes the id positionally and rejects/ignores the
`register`-style flag name; this was the first attempt's mistake, caught by the immediate `enroll` check
failing with "Authentication failure"). Confirmed the new secret enrolls. be-registrar's own attributes
(`hf.Registrar.Roles=client`, `hf.Registrar.Attributes=role`, `hf.Revoker=true`) are unchanged - this reissues
its SECRET only, grants no new privilege, and is exactly the recovery path `bootstrap_registrar.sh`'s own
"already registered" branch already documents for a lost secret.

## D-061 — Dev users' wallet rebuilt via the same identity-modify pattern; maxenrollments set to -1, not 1 (2026-09-22)
Owner-approved (a separate approval from D-060, since these are different CA identities): the 6 dev users were
already registered on ca_org1 from an earlier session with their `role` attribute already granted, but their
wallet (private keys) was gone and their one-time enrollment secret (`maxenrollments 1`, already consumed) could
never be reused. Reissued each identity's secret as the CA admin (`fabric-ca-client identity modify <user-id>
--secret <new>`), then enrolled immediately - no new CA identity created, no new role granted, same attributes
as before. **Found live:** setting `--maxenrollments 1` again does NOT reset the CA's internal "already
enrolled" counter for an identity that had already used its one enrollment - a fresh enroll with the new secret
was still refused with a generic "Authentication failure" (error code 20), which does not read as an
enrollment-limit error at all. Rejected staying at `1`: would have reproduced the exact same unrecoverable state
the moment this wallet is lost again. Chose `--maxenrollments -1` (unlimited) instead, verified this enrolls
successfully. This is a deliberate, dev/test-only deviation from A2's original `--id.maxenrollments 1` choice
(`scripts/fabric/enroll_users.sh`); a production deployment would want a bounded re-enrollment budget instead of
unlimited, but that is not this project's concern (CONSTRAINTS C-10's operational-key-management scope already
excludes production hardening). Wallet written to a scratch directory outside the repo, matching the existing
FABRIC_WALLET_DIR convention.

## D-062 — F2/F3 confirmed live against REAL Fabric: chaincode write + transfer-receiver auto-wrap (2026-09-22)
Follow-up to D-059 (which recorded F2/F3 verified only against `memory-ledger`), after D-060/D-061 restored the
Fabric CA registrar and the dev users' wallet. Re-ran with `SPRING_PROFILES_ACTIVE=dev` (real
`FabricLedgerService`, no `memory-ledger`), same master key as the earlier run (the collector's RSA keypair was
already persisted in Postgres from that run - a fresh master key would have made it undecryptable, found live
via `AEADBadTagException` before this was corrected). Both previously-unconfirmed checks now hold: (1) a real
chaincode write - `POST /api/evidence` returned a real evidence record and `GET .../history` showed a real
64-hex-character Fabric transaction id, not a stub; (2) the custody-transfer-receiver auto-wrap - the analyst
started with `metadataAvailable=false` (not yet a member, not yet the transfer receiver), collector initiated a
transfer to them, and within the FIRST one-second poll after that the analyst's `metadataAvailable` flipped to
`true` and their `/file` download matched the original upload byte-for-byte - proving `sync.EventProcessor`'s
`wrapForTransferReceiver` genuinely fires off G3's real Fabric chaincode-event stream, not just in a unit test.
All 9 checks from `docs/features/f2-f3-envelope-encryption-key-management.md` now hold against real Fabric, real
PostgreSQL and real IPFS. Fabric CA/wallet state as of this entry: `be-registrar` and all 6 dev users have a
fresh, working secret (D-060/D-061); the dev users' `maxenrollments` is now `-1` (D-061), a deliberate deviation
from A2's original `1`.

## D-063 — F5: plain Java retry loop (no new dependency), scoped to register()'s createEvidence only (2026-09-22)
Owner-approved deviation from FEATURE_LIST's literal "Spring Retry" tech note: chose a plain bounded retry loop
(3 attempts, fixed 200ms delay) mirroring `sync.EventSyncListener`'s already-approved tier-1 local retry exactly
(same constants, same shape), rather than adding `spring-retry` as a new dependency (C-04). Rejected Spring
Retry: its declarative `@Retryable`/`SimpleRetryPolicy` matches by exception CLASS, not by inspecting a code
inside the exception, so distinguishing a genuine Fabric-level MVCC conflict from the chaincode's own
`VERSION_CONFLICT` (both `LedgerException`) would need a custom `RetryPolicy` anyway - at which point the
library adds ceremony (an `@EnableRetry` context, and `register()` calling its own retried step would need to
move to a separate bean method for AOP self-invocation to be proxied) without buying anything the plain loop
does not already have, more simply and more testably.

**Scope, found by reading the chaincode, not assumed:** retry is applied ONLY to `EvidenceService.register()`'s
`ledger.createEvidence(...)` call. `CreateEvidence` (`chaincode/evidence/evidence.go`) reads and writes only keys
derived from ITS OWN arguments - `recordKey(evidenceID)` (a fresh, server-generated UUID, never reused) and CID
composite-index entries keyed by `(cid, evidenceID)` (distinct even when two DIFFERENT items share a CID, since
the evidenceID differs) - so it has NO shared, contended key with any other transaction. A genuine Fabric-level
MVCC read conflict on this specific call is therefore expected to be rare-to-never in practice, unlike the
VERSIONED writes (update/status/transfer/disposal), which all read-modify-write the SAME `recordKey(evidenceID)`
and could genuinely race. Retrying a versioned write blindly with the SAME `expectedVersion` after a real MVCC
conflict would not help - the chaincode's own version check would then correctly reject it, since by the time
retry's simulation runs the true current version has moved on; a MEANINGFUL retry there needs a re-read-rebuild
step specific to each call site's semantics, which is materially bigger than F5's brief tech line and was not
built. Documented as an explicit scope boundary (KNOWN_GAPS), not silently left out.

New `LedgerErrorCode.CONCURRENT_WRITE_CONFLICT` (owner-approved) distinguishes the gateway-level, retriable case
from the chaincode's own `VERSION_CONFLICT`, which is not - `FabricErrors.translate`'s `mvccCommit` branch now
returns the new code. Both map to HTTP 409, so no existing caller's behaviour changes.

**Live verification:** since `CreateEvidence` has no contended key, a genuine MVCC conflict could not be
naturally triggered live (confirmed by the chaincode analysis above, not by trying and failing) - contriving one
would mean adding artificial shared state to the chaincode, which is scope creep for a demonstration. Verified
instead with 6 focused unit tests using a Mockito-controlled `LedgerService` (succeeds on attempt 2, succeeds on
attempt 3, exhausts and propagates, never retries `VERSION_CONFLICT`, never retries an unrelated `ApiException`,
compensation still runs after exhaustion) - real control over the exact failure sequence, which a live trigger
could not have given anyway. A live regression check confirmed the wrapper is fully transparent in the normal
case: registration against real Fabric succeeded with zero retry log lines.

## D-064 — I1: chain-of-custody PDF built only from LedgerService + VerificationService; OpenPDF (2026-09-22)
Owner design point, confirmed before any code was written: the report must be producible with NO content
decryption, so a Judge or Auditor never wrapped in for a given item's file/metadata (F2/F3) still generates the
exact same report. `ReportService` is architecturally incapable of needing decrypted content, not just written
to avoid it - its only dependencies are `LedgerService` and `VerificationService`; it has no
`ContentKeyService`, `IpfsClient` or `EvidenceMetadata` reference at all. "Evidence details" is scoped to the
ledger-native record (case, type, status, custodian, timestamps) - the off-chain metadata document's free-text
description/location/notes is deliberately excluded, since that is the CONTENT F2/F3 protects, not a
chain-of-custody fact; this is the same design line I3 already draws ("see integrity status only, without
seeing the content").

**New dependency (owner-approved, C-04): OpenPDF 2.2.2** (`com.github.librepdf:openpdf`), not modern iText -
LGPL/MPL, free for any use, where iText 5+/7+ is AGPL or commercial-licensed. FEATURE_LIST named both as
acceptable.

**Found live, fixed before shipping:** the first layout crammed Version/TxID/Timestamp/Action/Actor/Role/Reason
into one 7-column table on A4 portrait - both the 64-hex-character transaction id and the 36-character actor
UUID wrapped mid-string, unreadable in the actual PDF (confirmed by reading the generated PDF back with
`PdfTextExtractor` in a unit test, not just eyeballing it). Fixed by splitting into two tables: a narrative
"custody timeline" (version/timestamp/action/role/reason) and a dedicated "actors and transaction ids" table
giving the two long tokens the width they need to render on one line.

**Verified:** 3 unit tests (`ReportServiceTest`, a real `InMemoryLedgerService` + real `FakeIpfsClient`, a
genuine 5-step timeline - register/status-change/transfer-initiate/transfer-accept/disposal-request) read the
generated PDF back with `PdfTextExtractor` and assert the ledger's own hashes, every transaction id, every
action and every actor id appear verbatim, that the verification result is present, and that the excluded
free-text description does NOT appear. A live run against real Fabric reproduced the same 5-step timeline
through the API, then generated the report AS THE JUDGE (confirmed via `metadataAvailable=false` that they held
no content key for this item) - the report generated successfully and every hash/tx-id/actor-id in the real PDF
matched the real `/history` response exactly.

## D-065 — L1: filled 3 real unit-test gaps, did not pad for coverage numbers (2026-09-22)
Surveyed every main class with no test file, then filtered to ones with actual untested LOGIC (not DTOs,
entities, or classes already covered live per their own feature docs). Found three genuine gaps, all pure
(no database needed), all previously exercised only through live curl checks that happened to hit one path:
`SearchService.sortProperty` (H1's sort allow-list - an untrusted client string must fall back safely, never
reach a raw property lookup; only the "happy path" sort keys were ever tried live), `DashboardService.
toLocalDate` (three different possible JDBC row shapes for a `date_trunc` aggregate - `java.sql.Timestamp`,
`Instant`, and a string-parse fallback; live testing against one real Postgres driver only ever exercises ONE
of the three), and `ActivityService.feed`'s caseId-blank-vs-null-vs-real branching and page-size cap. All three
get a mocked-repository unit test with an `ArgumentCaptor` on the `Pageable`/query actually built - deterministic,
fast, and covering edge cases (a SQL-injection-shaped sort string, a blank caseId, a 100000-row page request) a
curl script would not naturally think to send.

**Deliberately NOT touched:** `EvidenceProjectionSpecifications`/`AuditSpecifications` (H1/A6's actual predicate-
building) - these need a real `CriteriaBuilder`/`Root` to test meaningfully; mocking the Criteria API would prove
only that certain builder methods were called, not that the resulting QUERY is correct, which is exactly the
kind of coverage-number padding L1 was scoped to avoid. This is squarely what L2 (Testcontainers) is for instead
- a real Postgres, real Specification-built queries, real result sets. `VerificationService.overall()`'s
precedence logic (TAMPERED > NOT_FOUND > VERIFIED) was also left alone: `EvidenceServiceTest` already exercises
5 of its 9 input combinations, including the one that matters most (a proven mismatch outranks missing content)
- already strong, documented coverage, not a gap.

253 Java tests total (18 new).

## D-066 — L2: Testcontainers Postgres + the existing FakeIpfsClient/memory-ledger, no WireMock (2026-09-22)
FEATURE_LIST's own tech column for L2 already says "Real Postgres and MOCKED IPFS/Fabric" - Testcontainers was
never meant to run Fabric, only Postgres. Added `spring-boot-testcontainers`/`testcontainers-junit-jupiter`/
`testcontainers-postgresql` (owner-approved: the user's own instruction named Testcontainers explicitly).
Pinned `testcontainers.version=1.21.3` explicitly: Spring Boot 4.1.1's own BOM names `2.0.5`, but no such
artifact is published on Maven Central (checked live against search.maven.org) - 1.21.3 is the actual latest
release.

**Rejected: WireMock**, despite FEATURE_LIST naming it, for IPFS mocking specifically. `HttpIpfsClientTest`
already thoroughly covers `HttpIpfsClient`'s own HTTP-level behaviour (add/cat/pin-rm/version, various
success/failure/timeout cases) using a plain JDK `com.sun.net.httpserver.HttpServer` - a real HTTP server, zero
dependency, already proven in this codebase. Adding WireMock on top would duplicate that exact coverage for no
new signal. Fabric is mocked by the already-built `memory-ledger` profile (`InMemoryLedgerService`); IPFS by the
already-built `FakeIpfsClient` (overriding the real `HttpIpfsClient` bean via a `@TestConfiguration` `@Primary`
bean in the integration test) - both are the SAME doubles every other test in this suite already trusts, not new
mocking infrastructure.

New `integration/EvidenceRegistrationIntegrationTest`: `@SpringBootTest` + `@AutoConfigureMockMvc` +
`@Testcontainers` (a real Postgres 16 container, real Flyway migrations V1-V6 actually running) + `@Transactional`
(rolls back every write, including through MockMvc-triggered service calls, so the class-shared static container
stays clean between test methods - the standard Spring Testing pattern for this). Covers login -> create a case
-> register DIGITAL evidence (real multipart upload) -> read it back -> verify() -> confirm the case genuinely
lists it, through the REAL HTTP surface and REAL security filter chain - coverage no unit test offers (a real
Flyway migration bug, a real JPA mapping bug, or a real security-filter-chain break would all be invisible to a
mocked-repository unit test but caught here). Found live, fixed before this was "done": the test's own seeded
users needed `UserKeyService.provision()` called explicitly (F2/F3 mandates a wrapped key for the registrant;
`DevUserSeeder` does this in production, a test inserting users directly must do it too) - without it,
`register()` 500'd with an unhandled `IllegalStateException`, not a clean 4xx, confirming this is a real edge
worth having caught. 255 Java tests total.

## D-067 — L3: Compose for backend+Postgres+IPFS; Fabric stays a documented separate WSL step (2026-09-22)
Owner-requested risk assessment before writing any Compose/Testcontainers code, given what this session already
found about how fragile Fabric's own bootstrap is (D-060/D-061: a lost CA registrar secret and a `maxenrollments`
trap that silently prevents re-enrollment, both requiring real operator intervention even with previously-working
state). Investigated concretely rather than guessing: `docker info` confirmed WSL's Docker CLI and the Windows
side share one Docker Desktop engine, so Fabric's containers are not inherently WSL-bound - a point in favour.
But `fabric-samples/test-network/network.sh` (684 lines) revealed that even Hyperledger's own reference tooling
does not reduce network bring-up to `docker compose up`: it is crypto-material generation (cryptogen/fabric-ca,
before any container starts), THEN `docker compose up` for peers/orderer, THEN a separate `scripts/
createChannel.sh` with explicit `MAX_RETRY`/`CLI_DELAY` retry loops, THEN a separate `scripts/deployCC.sh` with
the same retry pattern across both orgs. This project adds MORE on top with zero existing automation: the custom
`evidence` chaincode's deployment, the CA registrar bootstrap, and per-user wallet enrollment - exactly the three
things that needed real firefighting this session, twice, with EXISTING crypto material. Automating all of that
reliably enough to survive a genuine, repeatable cold `docker compose up` is new engineering, not wiring together
existing pieces, and carries real risk of a new variant of the same class of CA/MSP sequencing problem, on a
final-year-project timeline with a working WSL-hosted alternative already proven repeatedly this session.

**Chose the fallback, owner-approved after the risk assessment was presented:** `docker-compose.yml` for
backend+Postgres+IPFS only, defaulting to the `memory-ledger` profile so `docker compose up` alone is a complete,
self-contained working demo - registration, encryption, search, dashboards, the PDF report, everything except a
real chain underneath. Fabric stays a documented separate step (`docs/FABRIC_RUNBOOK.md`, already proven); an
operator with that network running can point this stack at it by editing `.env`.

**New files:** `Dockerfile` (multi-stage: `eclipse-temurin:21-jdk` build stage using this project's own Maven
Wrapper - not a bare `maven:*` image, so the container build uses the identical toolchain as everywhere else;
`eclipse-temurin:21-jre` runtime, non-root user, `curl` added only for the healthcheck), `docker-compose.yml`
(postgres:16-alpine, `ipfs/kubo:v0.43.0` run with `--offline` per C-09 - never reproduce the public-network
mistake D-020 found, just because it is convenient to leave the flag off - and `backend` built from the
Dockerfile, `depends_on: condition: service_healthy` on both), `.env.example` (checked in) / `.env` (gitignored,
C-07 - no secret defaults committed).

**Verified with a genuine cold start** (`docker compose down -v` first, then `docker compose up --build` from
nothing): 3m31s total (dominated by the JDK base image pull, ~59s, and `dependency:go-offline`, ~104s - the
actual application build is under 10s). All three containers reported healthy in the correct dependency order;
logged in as a seeded dev user, registered DIGITAL evidence through the real multipart endpoint, and confirmed
`verify()` returned VERIFIED - the full encrypt/pin/hash/verify pipeline working inside the containerized stack,
not just against the WSL-hosted setup this session used everywhere else.

**Found live, documented as a real, honest limit, not glossed over:** a warm restart (`docker compose down`
without `-v`, then `up` again - containers recreated, volumes kept) proved that Postgres-native data (users,
cases) survives, but the evidence record itself does NOT: `memory-ledger` is a pure in-JVM-memory structure with
no persistence of its own (its own startup log already says so - "loses all data on restart"), and a fresh
backend container is a fresh JVM. This is not a bug in the compose file; it is the documented nature of the
reference ledger, now made concrete: anyone using this stack for a demo must not restart the `backend` service
mid-demo, or re-register evidence afterward. Connecting to a real, persistent Fabric network removes this limit.

## D-068 — K2: springdoc-openapi 2.8.6 generated from real controller annotations, not a hand-maintained spec (2026-09-22)
Owner-approved new dependency (C-04, explicitly named by the owner): `springdoc-openapi-starter-webmvc-ui`
2.8.6 (current latest on Maven Central, checked live). The spec is generated at runtime from `@Operation`/
`@ApiResponse`/`@Tag` annotations added directly on the real controller methods - the source of truth is the
actual code, so it cannot silently drift from what the endpoints really do the way a separately-maintained
document could. Every one of the 33 generated paths across all 8 tags (Auth, Cases, Evidence, Custody & Status,
Activity, Dashboard, Notifications, Audit) has a real, specific summary - none left as an auto-generated stub.

Per the owner's explicit instruction, the four named flows got the richest documentation, matching real
behaviour rather than aspiration: **register** (F2/F3 encryption is automatic and described in the operation
itself: a fresh content key generated, both artifacts encrypted before pinning, wrapped for the registrant and
current case members); **the two-step custody transfer** (initiate/accept/reject/cancel, each stating exactly
who must call it - current custodian vs. named receiver vs. original sender - and that custody does NOT move on
initiate); **the disposal approval flow** (request by COLLECTOR/PROSECUTOR, decide by JUDGE only, DISPOSED is
reachable no other way); and **`/verify` and `/report`**, both documented as needing NO content key at all
(F2/F3 design section 7 and I1), the one property that most differs from the ordinary "any read needs a wrapped
key" story. Every `@PreAuthorize` role requirement is stated in plain language in the matching operation's
description, since springdoc does not translate Spring Security SpEL into readable text automatically.

`SecurityConfig` gained a `permitAll` for `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs`, `/v3/api-docs/**`
- the documentation itself is browsable without a token; trying an operation from the "Try it out" UI still
needs a real one, exactly like any other API client (the global `bearerAuth` security requirement in
`OpenApiConfig`, overridden with `@SecurityRequirements` only on the two genuinely public auth endpoints).

**Postman collection rebuilt from scratch** (`postman/BlockEvidence.postman_collection.json`), covering every
endpoint live in the controllers today - all of Phase 1-2's original surface plus every Phase 3-5 addition
(cases, custody transfer, disposal, search/dashboard/activity/notifications/audit, the F2/F3 encrypted
register/file endpoints, /verify, /report) - not a stale Phase 1-2 snapshot. Structured as folders matching the
same Swagger tags, with a `baseUrl` environment variable and test scripts that chain real values (tokens, user
ids, case/evidence ids) between requests so it is genuinely runnable end to end, not just descriptive. A
`postman/sample-evidence.txt` file ships with the collection so the DIGITAL registration request runs unattended
via Newman without a file needing to be attached manually first.

**Found live, fixed before this was "done" (not simulated by planning ahead):** the collection's own version
numbers collided across folders on a full run - the Evidence folder's disposal flow left the shared
`{{evidenceId}}` at version 4 (DISPOSED) before the Custody & Status folder's `expectedVersion: 1` request ran
against the SAME item, which would have failed every request in that folder. Fixed by having Custody & Status
register its own independent item (`{{custodyEvidenceId}}`) - each folder is now independently runnable, not
just a single fixed end-to-end sequence. Also found: the DIGITAL-evidence file part had no file attached (an
empty `src`), which would silently fail as `FILE_REQUIRED` under Newman; fixed with the checked-in sample file.
The "Find by CID" request also depended on a `{{fileCid}}` variable nothing ever set - fixed by having the
register request's own test script capture it.

**Verified live**, in order: (1) Swagger UI loaded in a real browser (`/swagger-ui.html`) - correct title,
description, all 8 tags, all 33 operations with real summaries, zero console errors, and the "Verify integrity"
operation's full rich description rendered exactly as written when expanded; (2) `/v3/api-docs` returns valid
OpenAPI 3.1 JSON, 33 paths, the `bearerAuth` security scheme present, zero operations missing a summary; (3) the
FULL Postman collection run three times via Newman (`npx newman run`, no new project dependency - a one-off CLI
tool for verification only) against the real docker-compose stack (real Postgres, real IPFS, `memory-ledger`):
**42/42 requests executed, 0 failed, 20/20 assertions passed**, consistently across all three runs. This
includes the exact three flows the owner asked to confirm - login, register with encryption, generate a report
- plus every other endpoint in the API, chained together with real data end to end, not just individually.

## D-069

**Frontend integration scope (owner-directed): keep UI, rewire every data call to the real Spring backend,
delete what has no backend, add UI for backend features that had none.** The owner supplied a source repo
(`https://github.com/debopamghosh12/Crime_evidence`) whose `client/` (Next.js 16 / React 19 / Tailwind - NOT
React 18/MUI as first assumed; corrected after actually reading `client/package.json`, see the earlier mapping
report) is kept for its UI only. Its own `api/` (Node/Express) and `prisma/` (SQLite schema) are never copied,
never referenced, never run - confirmed by copying literally only `client/` into a new top-level `frontend/`.
Full endpoint-by-endpoint comparison (`GOAL`'s Part A/B/C split) is the owner's own explicit scoping, logged
here because CLAUDE.md asks every tradeoff to be logged even when the owner made the call, not just when I did:
about a third of the source app's surface (self-registration, QR codes, comments, lab results, generic access
requests, "CrimeBox" shared-keypair case-joining) has no backend equivalent anywhere in FEATURE_LIST.md and is
being cut outright rather than stubbed, matching the project's "no dead clicks" principle stated in the goal.

**API base URL: a single shared `frontend/src/lib/api.ts` axios instance, not per-page `${API}` constants.**
The source repo had two competing, already-broken mechanisms: a `next.config.ts` rewrite to a nonexistent
`:3001` Express backend, and a per-page `process.env.NEXT_PUBLIC_API_URL || "http://localhost:3000"` fallback
that defaults to Next's OWN dev-server port on failure (a real bug in the source repo, not a design choice to
preserve). Rejected keeping either: one shared axios instance with `baseURL` read once from
`NEXT_PUBLIC_API_URL` (`.env.local`, gitignored; `.env.local.example` committed) is a single source of truth,
and every page migrated onto it is one less place that can silently point at the wrong port. Default is
`http://localhost:8080`, matching `application.yml`'s `server.port`.

**CORS: an explicit origin allow-list bean, never `"*"`.** Added `CorsProperties`
(`blockevidence.cors.allowed-origins`, defaulting to `http://localhost:3000`) following the exact
`@ConfigurationProperties` record pattern already used by `JwtProperties`/`IpfsProperties`/etc., wired into
`SecurityConfig` via a `CorsConfigurationSource` bean and `.cors(...)` on the filter chain. `allowCredentials`
is `false` - this API is stateless bearer-token auth, never cookies, so credentialed CORS has nothing to
protect and enabling it anyway would only widen the attack surface for no benefit. Three existing `@WebMvcTest`
slice tests (`SecurityAndErrorFormatTest`, `EvidenceControllerTest`, `Phase3ControllersTest`) import
`SecurityConfig` directly and needed `CorsProperties` added to their `@EnableConfigurationProperties` list,
exactly as their own comment already explained for `JwtProperties` - a slice test doesn't run
`@ConfigurationPropertiesScan` from the main class. All 255 tests still pass after the change.

**Two pre-existing local-environment bugs found and fixed while getting the backend running for live
verification, unrelated to this session's own code changes:** `.env`'s `DB_PASSWORD` no longer matched
`be-postgres`'s actual container password (rotated in some earlier session without updating `.env` to match -
Postgres does not re-read `POSTGRES_PASSWORD` against an existing data volume on restart); and `.env` had no
`DB_URL` at all, so the app silently fell back to `application.yml`'s default of port 5432 - a DIFFERENT,
unrelated local Postgres install noted as a known trap in `docs/HANDOVER.md` Session 2 ("local Postgres owns
port 5432 so dev DB is container on 5433") - rather than the intended container on 5433. Fixed both in `.env`
(gitignored, no code change). Separately, the 6 dev users' password hashes in that Postgres volume predated the
current `BLOCKEVIDENCE_DEV_SEED_PASSWORD` value (`DevUserSeeder` only sets a password at user CREATION, "left
untouched" on every later boot per its own doc comment) - reset via direct SQL to a fresh BCrypt hash of the
current seed password, the same recovery pattern already used and documented in Session 8 for this identical
class of problem (a long-lived dev Postgres volume drifting from whatever `.env` currently says).

**Verified live (step 4, auth only - Part A's remaining items are still pending):** real Chrome browser,
`frontend/` on `:3000` against the real Spring backend on `:8080`. Login form now takes email (not the source
repo's `username`) and posts `POST /api/auth/login`; on success, a follow-up `GET /api/auth/me` fetches the
profile (the real backend's login response carries tokens only, never a user object) before redirecting to
`/dashboard/{realUserId}`. Network tab confirmed: CORS preflight `OPTIONS` 200, `POST /api/auth/login` 200,
`GET /api/auth/me` 200, no other origin ever contacted. Dashboard rendered "Welcome back, Dev COLLECTOR" /
"Role: COLLECTOR" - both real values from the backend's `MeResponse`, not placeholders. Sign Out cleared the
session and returned to `/login` cleanly. The only console message was a `fdprocessedid` hydration warning
(a form-autofill browser extension injecting an attribute before React hydrates) - confirmed unrelated to any
app code by inspecting the diff itself, a known benign false positive, not investigated further.

## D-070

**Frontend integration: evidence register/view + custody transfer (both steps), verified live end to end.**
`evidence/new/page.tsx` now sends a multipart request shaped exactly like `RegisterEvidenceRequest` - a
`"metadata"` JSON part (`caseId`/`type`/`description`/`location`/`collectedAt`/`notes`) plus, for DIGITAL only,
a single `"file"` part - replacing the source repo's flat form fields and `"files"` array entirely; the
"Testimonial" evidence-type option was dropped (the backend's `EvidenceType` enum has only PHYSICAL/DIGITAL).
`evidence/page.tsx` (list) and `evidence/[id]/page.tsx` (detail) were rewritten against the real
`EvidenceSearchResult`/`EvidenceResponse` shapes - both use plain user-id strings for custodian/creator, not
nested `{fullName}` objects (no user-lookup endpoint exists, A4 was never built), so the UI shows a truncated
id rather than inventing a name. `custody/page.tsx` was rewritten against the real
`GET /api/transfers/pending` (`PendingTransferResponse[]`): there is no separate "transfer id" in this design
(one pending transfer lives on the evidence record itself), so accept/reject are keyed by `evidenceId` +
`expectedVersion`, not a transfer id as the source repo assumed. The backend also has no "my outgoing pending
transfers" endpoint - rather than fabricate one client-side, the Outgoing panel was replaced with a one-line
note saying so (§ goal's "no dead click" principle applied to a whole panel, not just a button).

**Found and fixed while verifying live, none caused by this session's own code:**
1. `be-ipfs` (Docker) was stopped from the prior K2 session - register failed with a real, correctly-surfaced
   backend error (`"IPFS node not reachable while storing content"`), proving the error-message plumbing works
   end to end before the fix (started the container) was even applied.
2. The collector's RSA private key in Postgres was encrypted under an EARLIER session's master key (the exact
   class of drift Session 8 already hit and documented) - registering under the CURRENT `.env` master key threw
   `AEADBadTagException` on decrypt. Fixed the same way: cleared `user_keys`/`evidence_content_keys` (dev/test
   key material only, not evidence) and restarted so `DevUserSeeder` reprovisioned fresh keys.
3. `custody/page.tsx`'s `handleApprove`/`handleReject` (kept from the source repo) called native
   `confirm()`/`alert()`/`prompt()`. These block the ENTIRE page - including this session's own CDP-driven
   browser automation, which hung on a `computer:screenshot` call after a click that had actually already
   succeeded server-side (confirmed independently via a direct API call while the tab was stuck). Root cause,
   not just a symptom to work around: blocking dialogs are bad UI practice regardless of who's driving the
   browser. Replaced with inline state (a dismissable success/error banner, an inline note field for reject)
   in every file touched this pass - not just here.

**Verified live, in order:** registered a real DIGITAL item with a real uploaded file as COLLECTOR - the
returned `fileSha256` matched the backend's own `/verify` endpoint's `actualSha256` exactly (VERIFIED on both
file and metadata), and downloading the file back (F2/F3 decrypt-on-download) produced a byte-identical copy of
the original upload (`diff` confirmed). Then initiated a transfer to FORENSIC_ANALYST, accepted it in a second
browser session as that user (custody moved: `currentCustodian` changed, `version` incremented, confirmed via
direct API call since the accepting tab was stuck on the alert() bug above), then initiated a transfer back and
rejected it with a note (custody correctly stayed with the sender). Every step cross-checked against the real
backend directly, not just trusted from the UI.

## D-071

**D-070's tests were against `memory-ledger`, not real Fabric - caught by the owner, re-verified against the
real network before calling either "done."** The backend had been started with
`SPRING_PROFILES_ACTIVE=dev,memory-ledger` (deliberately, for fast iteration while wiring the frontend's request
shapes) and D-070's write-up said so, but never called that out as a gap needing a real-Fabric re-run before the
checkpoint - the owner asked directly, plainly, and that's a fair catch: this project's own standard (every
other phase re-verified against real Fabric before being called done) applies here too, frontend or not.

**Found while re-establishing real-Fabric connectivity for this recheck:** the WSL Fabric network (peer0.org1,
peer0.org2, orderer, 3 CAs) was already running, but `.env` had none of `FABRIC_TLS_CERT_PATH`/`FABRIC_CERT_PATH`/
`FABRIC_KEY_PATH`/`FABRIC_WALLET_DIR` set - lost between sessions the same way other dev-only local state has
been before (Session 8, this session's own DB_PASSWORD/DB_URL earlier). The per-user wallet (6 UUID-named
identities, `cert.pem`+`key.pem` each) survived in this session's own scratchpad from the earlier real-Fabric
F2/F3 work and was reused rather than re-enrolled - no new CA identity, no credential reissuance, matching this
project's standing preference for the least-privileged recovery available. Wrote the resulting real values back
to `.env` with **single quotes around every path** - a real bug found live: `set -a && source .env` (this
session's own launch pattern) silently swallows unquoted backslashes as shell escape characters, so a UNC path
written unquoted arrives at the JVM with every path separator stripped (`\\wsl.localhost\Ubuntu\...` becomes
`wsl.localhostUbuntu...`) - `NoSuchFileException` with no indication the value was ever mangled. This is a real
trap for any Windows-path-shaped env var loaded via `source` in this project's dev workflow, not specific to
Fabric; worth remembering for any future `.env` value with backslashes.

**Re-verified against real Fabric (`ledger` health: "chaincode evidence answering on channel crimechannel via
localhost:7051 as Org1MSP"):** registered a new DIGITAL item through the frontend - `/verify` VERIFIED, and
`/history` showed one real 66-hex-character transaction id (`ec24c39b...`), not a memory-ledger placeholder.
Initiated a custody transfer through the frontend and accepted it as FORENSIC_ANALYST in a second session -
`/history` shows three real, distinct transaction ids (CREATED, TRANSFER_INITIATED, TRANSFER_ACCEPTED), each
with the correct real actor id/role and timestamp. Both the register and custody-transfer wiring are now
confirmed against the same standard as every other phase in this project - not just memory-ledger.

## D-072

**Frontend integration: Cases (list/create/view/update), verified live against real Fabric.** `cases/page.tsx`
and `cases/[caseId]/page.tsx` rewritten against the real `CaseResponse`/`CreateCaseRequest`/`UpdateCaseRequest`
shapes - the source repo's `{title, description}` create form was missing two backend-required fields
(`caseNumber`, `leadOfficerId`, both mandatory), added as plain text inputs (no user-lookup endpoint for picking
a lead officer, same limitation as custody transfer's recipient field). The edit form's status dropdown was
removed entirely rather than kept and silently doing nothing: `UpdateCaseRequest` has no `status` field at all
- closing/reopening a case (E4) was never built, so there is nothing to submit it to. Replaced the "Crime Boxes"
section (Part B, no backend equivalent) with a real one: `CaseResponse.evidenceIds`, already returned by the
same GET, linking straight to each evidence item's detail page (E3) - no extra endpoint needed. Case officer
add/remove (also on this page in the source repo) is intentionally NOT wired here - it's a named Part C
addition, done in its own pass once the rest of Part A is through.

**Verified live** (as PROSECUTOR, one of the two roles `Permissions.MANAGE_CASES` allows, against the real
Fabric-backed server from D-071): created a case with all four real fields through the actual form, confirmed
it appeared correctly in the list and detail views: title-only edit saved and reread back from the API exactly
as sent (no status field ever offered to lose). Opened an existing case with real evidence already linked
(`FE-TEST-001`, populated across earlier checkpoints) and confirmed all 3 real evidence ids - including both a
memory-ledger-era item and D-071's real-Fabric item - listed correctly and linked through to their own detail
pages, proving E3's off-chain link table doesn't care which ledger backend registered the evidence.

## D-073

**Frontend integration: Dashboard, and CrimeBox deleted entirely (Part B), verified live against real Fabric.**
`dashboard/[userId]/page.tsx` was the source repo's entire CrimeBox onboarding surface - the "Join/Create a
Crime Box" landing state, the head-officer-only key-reveal panel, all of it - not a small piece of the page but
the page's main branch. Rewrote it against the real `DashboardResponse` (H2: totalEvidence, byStatus, byType,
byCase, activityByDay) and a small live preview from `ActivityEntryResponse` (H3), then deleted
`CrimeBoxContext.tsx` and `components/crime-box/` outright and removed `CrimeBoxProvider` from the root layout -
Part B says "remove entirely, including any onboarding step that assumes it exists," and the CrimeBoxProvider
wrapping the whole app was exactly that. A repo-wide grep after the edit confirms zero remaining references
outside one explanatory comment.

**Verified live** (as PROSECUTOR, against the real Fabric-backed server): the dashboard rendered real,
non-trivial aggregate numbers accumulated across this dev database's whole history (117 total evidence items,
a real status/type breakdown) and a real activity feed showing this session's own just-created transfer events
with correct actor ids/roles/case numbers. Cross-checked `totalEvidence` and `byStatus` against a direct
`GET /api/dashboard` call - exact match. No console errors on load.

## D-074

**Frontend integration: Notifications + Activity Feed, verified live against real Fabric.** Both rewritten
against the real `NotificationResponse`/`ActivityEntryResponse` shapes. Two real gaps found while doing it:
`NotificationResponse` has no boolean `read` field - "read" is derived from `readAt` being non-null, which the
source repo's shape didn't have at all; and there is **no bulk "mark all read" endpoint** on the backend (the
source repo called a `PUT .../read-all` that doesn't exist here). Rather than drop the button or fake success,
`markAllRead` loops the real single-notification endpoint over every currently-unread id
(`Promise.all(...map(id => api.post(...)))`) - N real calls instead of one, but every one of them genuinely
happens; nothing here is simulated. Activity Feed's actor display is the same pattern already established for
custody/evidence: a truncated real user id + real role, never a fabricated display name (no user-lookup
endpoint, A4 was never built).

**Verified live** (against the real Fabric-backed server): Activity Feed as PROSECUTOR showed this session's
own real custody-transfer and disposal events, newest first, with correct action colors, real actor ids/roles,
real reasons, and correct relative timestamps. Notifications as FORENSIC_ANALYST showed 6 real unread items
(pending transfers, a disposal-approved event, a status change) plus one already-read item rendered dimmed;
clicking "Mark all read" cleared all 6, confirmed with a direct `GET /api/notifications` call afterward showing
`0 unread / 7` total - the loop-of-real-calls approach genuinely persisted, not just updated client-side state.

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

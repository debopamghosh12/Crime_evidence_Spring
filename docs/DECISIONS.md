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

# Bug: application fails to start, PostgreSQL rejects the connection ("TimeZone")

**Found:** 2026-09-21, first `./mvnw spring-boot:run` against the Postgres 16 container, during Phase 1
live verification. **Fixed:** same day. **Feature affected:** A1 / K3 (anything needing the database).

## Symptom

The application stopped during Flyway startup with:

```
Caused by: org.postgresql.util.PSQLException: FATAL: invalid value for parameter "TimeZone": "Asia/Calcutta"
	at org.postgresql.core.v3.QueryExecutorImpl.receiveErrorResponse(QueryExecutorImpl.java:2993)
	...
	at org.flywaydb.core.internal.jdbc.JdbcUtils.openConnection(JdbcUtils.java:73)
```
Exit code 1. Unit tests were unaffected (they never open a database connection), which is why this only
appeared in the live run.

## Diagnosis

1. The message names the parameter (`TimeZone`) and value (`Asia/Calcutta`), and comes from the *server*
   (`FATAL:`), so the driver reached PostgreSQL and was refused after the startup handshake.
2. The PostgreSQL JDBC driver sends the client JVM's default timezone as a startup parameter on every
   connection. On this machine (Windows, India locale) the JVM default is the legacy alias
   `Asia/Calcutta`, which the `postgres:16` image does not accept. (The modern name is `Asia/Kolkata`.)
3. So the failure depends on the host's zone, not on the code, and would hit any developer or server
   whose JVM reports a legacy alias, including IntelliJ run configurations.

## What was considered

| Option | Verdict |
|---|---|
| `-Duser.timezone=UTC` in every launch config | Rejected: every IntelliJ run config, Docker command and CI job would need it; forgetting one reproduces the crash. |
| `SET TIME ZONE` on the server / in JDBC URL | Rejected: the zone is sent in the startup packet, before any SQL runs. |
| `TimeZone.setDefault(UTC)` first thing in `main()` | **Chosen.** Independent of how the app is launched. All persisted times are `Instant`/`TIMESTAMPTZ`, so UTC is also the correct zone. |

## Fix

`BlockEvidenceApplication.main` now calls `TimeZone.setDefault(TimeZone.getTimeZone("UTC"))` before
`SpringApplication.run`, with a comment explaining why. Logged as DECISIONS D-010.

## Verification

Command: `./mvnw spring-boot:run` (env: `DB_URL`, `DB_PASSWORD`, `BLOCKEVIDENCE_JWT_SECRET`,
`BLOCKEVIDENCE_DEV_SEED_PASSWORD`, `SPRING_PROFILES_ACTIVE=dev`) against `postgres:16`.

Actual output after the fix:
```
2026-09-21T18:30:52.146Z  INFO ... c.b.backend.BlockEvidenceApplication : The following 1 profile is active: "dev"
2026-09-21T18:30:55.581Z  INFO ... org.flywaydb.core.FlywayExecutor     : Database: jdbc:postgresql://localhost:5433/blockevidence (PostgreSQL 16.15)
2026-09-21T18:31:00.902Z  INFO ... c.b.backend.BlockEvidenceApplication : Started BlockEvidenceApplication in 9.598 seconds
```
The `Z` timestamps also show the JVM zone is now UTC. Flyway then applied V1 and Hibernate's
`ddl-auto=validate` passed (the application would not have reached "Started" otherwise).

## Not covered

There is no automated regression test for this: it needs a real PostgreSQL server and a host with a legacy
zone. It will be catchable by the Testcontainers integration test planned for L2 only if that test also
forces `-Duser.timezone=Asia/Calcutta`; noted for Phase 5.

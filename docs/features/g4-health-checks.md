# G4: Health checks (Fabric, IPFS, database)

**FEATURE_LIST:** G4 (P1, new). **Phase:** 1. **Status:** done, verified 2026-09-22.

## Scoping
"Report Fabric, IPFS and database connectivity" via Spring Boot Actuator. Questions to settle: what a stub
ledger should report, what anonymous callers may see, and which other actuator endpoints stay closed.

## What was built
- `ledger/LedgerHealthIndicator` (component `ledger`) -> `LedgerService.health()`; `storage/IpfsHealthIndicator`
  (component `ipfs`) -> `IpfsClient.isReachable()`; `db` is Spring's built-in indicator. The indicators go
  through the interfaces, never around them (C-01).
- `application.yml`: only `health` is exposed; `show-details: when-authorized`, `roles: ADMIN`.
- `SecurityConfig`: `/actuator/health[/**]` is public so load balancers and orchestrators can probe it.

## Decisions and findings
- **Ledger stub = UNKNOWN, not DOWN** (D-008). Spring's default aggregation ignores UNKNOWN while others are
  UP, and DOWN still wins. Confirmed by unit test and live (overall 200 with ledger UNKNOWN).
- **Anonymous and non-admin callers see the overall status only.** ADMIN sees components. Note: ADMIN's
  view also includes Spring's `diskSpace` detail, which discloses a server filesystem path
  (`E:\FINAL YEAR PROJECT\.` in the dev run). Acceptable for ADMIN-only; revisit for production.
- **Boot 4 moved the health API** to `org.springframework.boot.health.contributor`, and
  `SimpleStatusAggregator` is deprecated for removal: the test uses `StatusAggregator.getDefault()`.
- DOWN maps to HTTP 503, so orchestrators treat a dead IPFS or DB as unhealthy automatically.
- `/actuator/env` and all other actuator endpoints are not exposed (404 for ADMIN, 401 anonymous).

## Verification
`HealthIndicatorsTest` (5) and `HttpIpfsClientTest`. Live (real Postgres + real Kubo), from
`docs/TEST_CHECKLIST.md` section 6:
```
GET /actuator/health (anonymous)             HTTP 200 {"groups":["liveness","readiness"],"status":"UP"}
GET /actuator/health (ADMIN)                 HTTP 200 ... "db":{...,"status":"UP"} "ipfs":{"status":"UP"} "ledger":{...,"status":"UNKNOWN"} ... "status":"UP"
GET /actuator/health (COLLECTOR)             HTTP 200 {"groups":["liveness","readiness"],"status":"UP"}     (no components)
docker stop be-ipfs
GET /actuator/health (anonymous)             HTTP 503 {"groups":["liveness","readiness"],"status":"DOWN"}
GET /actuator/env    (ADMIN)                 HTTP 404 {"error":"NOT_FOUND",...}
```

## Known limits
The DB check uses Spring's default validation query; a slow database delays the probe. No Fabric probing
until the real implementation exists.

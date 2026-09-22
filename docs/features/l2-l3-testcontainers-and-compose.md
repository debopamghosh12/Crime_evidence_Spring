# L2/L3: Integration tests (Testcontainers) and Docker Compose

**FEATURE_LIST:** L2 (P1, "Testcontainers, WireMock"), L3 (P1, "docker-compose.yml, Dockerfile"). **Phase:** 5.
**Status:** done. L3 built as the Fabric-stays-separate fallback, per an owner-requested risk assessment done
before any Compose code was written - see DECISIONS D-066/D-067 for the full reasoning.

## The Fabric-in-Compose risk assessment (before writing anything)
The owner asked for an honest read on whether containerizing Fabric into Compose was safe to attempt, given what
this session had already found about A2's CA/wallet fragility (D-060/D-061: two real recovery interventions
needed even with previously-working state). Investigated concretely: `docker info` confirmed one shared Docker
Desktop engine (a point in favour), but `fabric-samples/test-network/network.sh` (684 lines) showed that even
Hyperledger's own reference tooling needs a scripted, multi-stage, retry-looped orchestration (crypto generation,
then compose up, then a channel-creation script, then a chaincode-deployment script) to bring the network up -
not a single `docker compose up`. This project adds a custom chaincode, a CA registrar, and per-user wallet
enrollment on top, none of which has any existing automation and all of which broke at least once this session.
Conclusion: real, material risk of sinking time reproducing that sequencing reliably for a genuine cold start, for
a final-year-project timeline where a working WSL-hosted alternative already exists. Owner approved the fallback.

## L2: integration tests
New `integration/EvidenceRegistrationIntegrationTest`, matching FEATURE_LIST's own tech note exactly ("real
Postgres and MOCKED IPFS/Fabric"):
- **Real Postgres** via Testcontainers (`spring-boot-testcontainers`'s `@ServiceConnection`) - real Flyway
  migrations V1-V6 actually run, real constraints actually enforced.
- **Mocked Fabric**: the already-built `memory-ledger` profile (`InMemoryLedgerService`) - no new code.
- **Mocked IPFS**: the already-built `FakeIpfsClient`, wired in via a `@TestConfiguration` `@Primary` bean -
  **not WireMock**, despite FEATURE_LIST naming it. `HttpIpfsClientTest` already thoroughly covers
  `HttpIpfsClient`'s own HTTP-level behaviour using a plain JDK `HttpServer`; adding WireMock on top would
  duplicate that coverage, not extend it (DECISIONS D-066).

The test runs login -> create a case -> register DIGITAL evidence (a real multipart upload) -> read it back ->
`verify()` -> confirm the case lists it, through the real HTTP surface, real security filter chain, and real
database - genuinely new coverage no unit test offers. `@Transactional` on the test class rolls back every write
(including through MockMvc-triggered service calls) so the class-shared static container stays clean between
test methods, the standard Spring Testing pattern for this.

**Found live before this was "done":** the test's directly-inserted users needed `UserKeyService.provision()`
called explicitly - F2/F3 mandates a wrapped content key for the registrant, which `DevUserSeeder` provisions in
production but a test bypassing it must do itself. Without it, `register()` 500'd with an unhandled
`IllegalStateException` rather than a clean error.

## L3: Docker Compose
`Dockerfile` (multi-stage, using this project's own Maven Wrapper so the container build matches the toolchain
used everywhere else) + `docker-compose.yml` (postgres, `ipfs/kubo` run `--offline` per C-09, backend) +
`.env.example` (checked in) / `.env` (gitignored, C-07). Defaults to `dev,memory-ledger` profiles, so
`docker compose up` alone is a complete, self-contained working demo - no Fabric network required to try
anything except a real chain underneath.

### Verified with a genuine cold start
`docker compose down -v` (clean slate) then `docker compose up --build`:
```
real    3m31.162s
```
Dominated by the JDK base image pull (~59s) and `dependency:go-offline` (~104s) - the actual Maven package step
is under 10s. All three containers reported healthy in the correct dependency order (postgres/ipfs healthy
first, then backend). Logged in as a seeded dev user (`collector@blockevidence.local`), registered DIGITAL
evidence through the real multipart endpoint, and confirmed `GET .../verify` returned `VERIFIED` - the full
encrypt/pin/hash/verify pipeline working inside the containerized stack itself, not just the WSL-hosted setup
used everywhere else this session.

### Known limit, found live (not glossed over)
A warm restart (`docker compose down` without `-v`, then `up` again - containers recreated, volumes kept) shows
Postgres-native data (users, cases) survives, but the evidence record does NOT: `memory-ledger` is a plain
in-JVM-memory structure with no persistence of its own (its own startup log already says "loses all data on
restart"), and a fresh backend container is a fresh, empty ledger. **Do not restart the `backend` service
mid-demo.** Connecting to a real, persistent Fabric network (edit `.env`, drop `memory-ledger` from
`SPRING_PROFILES_ACTIVE`) removes this limit entirely.

## What was NOT built
- Fabric is not part of `docker-compose.yml` - stays a documented separate WSL step (`docs/FABRIC_RUNBOOK.md`).
- WireMock was not added (see L2 above).
- No CI pipeline (L4) - out of scope for this phase.

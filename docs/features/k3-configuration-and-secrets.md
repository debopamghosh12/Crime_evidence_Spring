# K3: Configuration and secrets

**FEATURE_LIST:** K3 (P0, exists in prototype). **Phase:** 1 (kept in Phase 1 by the project owner).
**Status:** done, verified 2026-09-22.

## Scoping
"Environment variables and profiles for ports, IPFS URL, channel, chaincode, identity. No secrets in
code." Constraint C-07 makes the last sentence a hard rule. Identity settings belong to the real Fabric
service (Phase 2/3), so Phase 1 covers everything else.

## What was built
- `src/main/resources/application.yml`: every value is an env reference. Required (no usable default):
  `DB_PASSWORD`, `BLOCKEVIDENCE_JWT_SECRET`. Defaulted, non-secret: `DB_URL`, `DB_USER`, `SERVER_PORT`,
  `IPFS_API_URL`, `FABRIC_CHANNEL` (`crimechannel`), `FABRIC_CHAINCODE` (`basic`), `JWT_ACCESS_TTL`
  (`15m`), `JWT_REFRESH_TTL` (`7d`).
- `config/JwtProperties`, `IpfsProperties`, `FabricProperties`: typed `@ConfigurationProperties` records
  with `@Validated`, registered by `@ConfigurationPropertiesScan`.
- `application-dev.yml`: only the dev seed-password binding (`BLOCKEVIDENCE_DEV_SEED_PASSWORD`).
- `.gitignore` additions: `.env`, `*.env`, `application-local.yml`.
- Profiles: `dev` exists; there is deliberately no `prod` file, because production is the default
  configuration and everything environment-specific arrives via env vars.

## Decisions and things that went wrong
- **Fail fast on a missing secret.** `JwtProperties.secret` has no default. With the env var unset it
  binds to `""` and `@NotBlank`/`@Size(min=32)` abort startup with a clear message, instead of falling
  back to some built-in key. `DB_PASSWORD` has no default either.
- **`JwtProperties.toString()` is overridden to print `<redacted>`**: a record's generated `toString`
  would put the signing key into any log line that dumps the properties. Tested.
- **Tests must not contain key literals either** (C-07): `TestSecrets` generates random keys per run
  (D-015). An earlier version of the tests used fixed fake keys; replaced.
- Initializr rejected `configurationFileFormat=yml` (400), so the generated `application.properties` was
  replaced with `application.yml` by hand.
- Port 5432 was already taken on the development machine by a local Postgres, so live runs use a
  container on 5433 through `DB_URL`. Nothing in the code assumes a port.
- Live run exposed a host-dependent startup failure (JVM zone `Asia/Calcutta` rejected by PostgreSQL 16):
  see `docs/bugs/jvm-timezone-postgres.md`.

## Verification
`JwtPropertiesTest` (4 tests: empty rejected, 31 chars rejected, 32 accepted, `toString` redacts).
Live:
```
$ unset BLOCKEVIDENCE_JWT_SECRET; ./mvnw spring-boot:run        -> exit 1
APPLICATION FAILED TO START
Binding to target com.blockevidence.backend.config.JwtProperties failed:
    Property: blockevidence.jwt.secret
    Value: ""
    Reason: must be at least 32 characters (HS256 needs a 256-bit key)
    Reason: must not be blank
```
And with all variables set: `Started BlockEvidenceApplication in 9.598 seconds`. See
`docs/TEST_CHECKLIST.md` sections 0 and 2.

## Known limits
No `prod` profile file and no secret manager integration; env vars only. Fabric identity settings are
not defined yet.

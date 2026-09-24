# Bug: `FABRIC_*` paths broken two ways - quoted UNC paths mangled by the loader, and an ephemeral wallet dir

**Found:** 2026-09-24, reported by the project owner from a live run against real Fabric. **Fixed:** same
day. **Feature affected:** A2 (per-user Fabric identities), FabricLedgerService, EventSyncListener.

## Symptom

Two separate, simultaneous failures:

1. `POST /api/evidence` crashed with:
   ```
   java.nio.file.InvalidPathException: Illegal char <:> at index 2
   ```
   thrown from `FileWalletIdentityStore.find()` calling `Path.of()` on `FABRIC_WALLET_DIR`.
2. A background failure logged repeatedly (1000+ consecutive times) in `FabricLedgerService` /
   `EventSyncListener`:
   ```
   Cannot connect to Fabric (service identity): NoSuchFileException
   ```
   pointing at `FABRIC_CERT_PATH`.

## Diagnosis

`.env` had all four `FABRIC_*_PATH` values as **Windows UNC paths reaching into WSL**, single-quoted:
```
FABRIC_TLS_CERT_PATH='\\wsl.localhost\Ubuntu\home\debop\...\ca.crt'
...
FABRIC_WALLET_DIR='C:\Users\debop\AppData\Local\Temp\claude\...\scratchpad\wallet'
```

Two independent root causes, not one:

1. **Literal quotes.** Spring resolves `${FABRIC_WALLET_DIR:}` straight from the OS process
   environment - there is no dotenv library in this project (confirmed: `grep -i dotenv pom.xml` finds
   nothing) and no quote-stripping happens anywhere in the load path. Whatever loaded these into the
   process environment on Windows did not strip the surrounding single quotes, so the value arrived as
   `'C:\Users\...` - literal opening quote included. `'` is index 0, `C` is index 1, `:` is index 2,
   which is exactly the reported `Illegal char <:> at index 2`. (Re-confirmed this session: bash's own
   `source .env` *does* strip these quotes correctly - the bug is in the Windows-side loader, not in the
   quoting convention itself. `TESTING_GUIDE.md` already separately documents the opposite failure mode,
   D-071: sourcing the same values *unquoted* in bash swallows the backslashes instead.)
2. **The wallet directory didn't exist.** `FABRIC_WALLET_DIR` pointed at a session-scratchpad path
   (`AppData\Local\Temp\claude\...\scratchpad\wallet`) that is deleted between sessions by design - it
   was never a durable location, so even with the quoting fixed, `FileWalletIdentityStore` would find
   nothing there. This is unrelated to the quoting bug and would have surfaced on its own.

A third issue surfaced only while fixing this: the actual Fabric network lives inside WSL's own
filesystem (`/home/debop/crime-evidence-mgmt/fabric-samples/...`), so a Windows-native JVM can only ever
reach it through a `\\wsl.localhost\...` UNC path in the first place - fragile by construction, not just
because of the quoting bug (see D-082).

## What was considered

| Option | Verdict |
|---|---|
| Strip quotes, keep Windows-native UNC paths, keep the scratchpad wallet dir | Rejected: fixes bug #1 only; bug #2 (missing wallet) and the underlying UNC fragility remain. |
| Move backend execution into WSL, plain POSIX paths, durable wallet dir | **Chosen**, user confirmed via `AskUserQuestion`. Removes both bugs and the UNC fragility at the source: no quoting to strip, no cross-filesystem path translation, ever. |

## Fix

1. **`.env`**: all four `FABRIC_*_PATH` values rewritten as unquoted, plain POSIX paths under
   `/home/debop/crime-evidence-mgmt/fabric-samples/...` (D-082). `FABRIC_WALLET_DIR` moved to
   `/home/debop/blockevidence-wallet`, a durable WSL-home directory outside any repo checkout (D-083, not
   the repo-root `wallet/` folder originally suggested - that would violate C-07/A2_IDENTITY_DESIGN.md).
2. **`scripts/fabric/enroll_users.sh`**: fixed a second, previously-latent bug found while re-populating
   the new wallet directory - its idempotency check only looks at the local wallet, so it tried to
   re-register all 6 dev users, who were still registered at the CA from before the old wallet was lost.
   Added a fallback to `fabric-ca-client identity modify --secret ... --maxenrollments -1` when `register`
   fails with "already registered" (D-084), then ran it successfully against the new wallet directory.
3. Backend now started from inside WSL (`SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run`, sourcing
   the corrected `.env`), not from Windows PowerShell. `README.md` and `TESTING_GUIDE.md` updated to
   document this as the going-forward method (D-082).

## Verification

Command: `SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run` from WSL Ubuntu, against
`/mnt/e/FINAL YEAR PROJECT`, with the corrected `.env` sourced.

Startup log, actual output:
```
2026-09-24T07:40:27.656Z  INFO ... c.b.backend.BlockEvidenceApplication  : Started BlockEvidenceApplication in 75.801 seconds
2026-09-24T07:40:35.002Z  INFO ... c.b.backend.ledger.FabricLedgerService : Connected to Fabric peer localhost:7051 (channel crimechannel, chaincode evidence, msp Org1MSP, service identity)
```
No `InvalidPathException`, no `NoSuchFileException`, and no repeat of the warning afterward (log file
stayed at the same line count after the connection message - the loop is gone, not just quiet).

`GET /actuator/health` as ADMIN, actual output (excerpt):
```json
"eventSync": { "details": { "consecutiveFailures": 0, ... }, "status": "UP" },
"ledger": { "details": { "detail": "chaincode evidence answering on channel crimechannel via localhost:7051 as Org1MSP" }, "status": "UP" },
"wallet": { "details": { "detail": "No wallet identity expires within 30 days" }, "status": "UP" }
```

`POST /api/evidence` as `collector`, actual output: `HTTP_STATUS:201`, with a real
`metadataCid`/`fileCid` (IPFS) and `metadataSha256`/`fileSha256`, `status":"COLLECTED"` - a genuine,
end-to-end register against real Fabric + real IPFS, not a stub.

## Not covered

No automated regression test: this needs a live WSL Fabric network, which nothing in the current test
suite spins up (L2/Testcontainers is Phase 5). The `enroll_users.sh` already-registered fallback is
exercised only by this manual run; if the CA's error text for that case ever changes, the `grep -q
"already registered"` match in the script would silently stop catching it and fall through to the
original abort-with-real-error behavior (safe failure mode, just not automatic recovery).

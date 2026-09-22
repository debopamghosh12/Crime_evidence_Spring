# Fabric runbook

How the ledger side is run, deployed and verified on this machine. Everything here was done for real on
2026-09-22; nothing is aspirational. Scripts live in `chaincode/scripts/` (run inside WSL) and `scripts/live/`.

## 1. Where things are (corrects an assumption in CHAINCODE_DESIGN.md section 1)

The working Fabric toolchain is **inside WSL Ubuntu**, not on `E:`:

| Thing | Location |
|---|---|
| `fabric-samples` (with `network.sh`, `bin/peer`, `config/`, crypto material) | `/home/debop/crime-evidence-mgmt/fabric-samples` |
| The original Node.js prototype (`app.js`, `wallet/`, `postman_collection.json`) | `/home/debop/crime-evidence-mgmt/backend` |
| Network | Docker Desktop containers on network `fabric_test`: `ca_org1 ca_org2 ca_orderer orderer.example.com peer0.org1.example.com peer0.org2.example.com`; volumes persist the ledger |
| Channel | `crimechannel` (Org1MSP + Org2MSP, LevelDB state DB, endorsement policy: majority of the two orgs = both) |
| Old chaincode | `basic` v1.0 seq 1 (still committed, untouched; its source is not on disk) |
| New chaincode | **`evidence`** v1.1 seq 2 (v1.0 seq 1 was superseded, see DECISIONS D-032) |

The copies of `fabric-samples` under `E:\FinalYrProjects\...` contain only `test-network/{compose,organizations}` and are
NOT used.

## 2. Bring the network up (revive; never `network.sh down`)

```bash
docker start ca_orderer ca_org1 ca_org2; sleep 3
docker start orderer.example.com;        sleep 3
docker start peer0.org1.example.com peer0.org2.example.com     # chaincode containers restart on demand
docker start be-postgres be-ipfs         # be-ipfs was created with: ipfs/kubo daemon --offline (constraint C-09)
```
Check (inside WSL): `. chaincode/scripts/fab_env.sh; org1; peer channel list; peer lifecycle chaincode querycommitted -C crimechannel`.
Right after a start the peers log `SERVICE_UNAVAILABLE` from the orderer for a few seconds while it elects a leader; that is normal.
Revived on 2026-09-22 after ~4 months stopped: channel intact at height 48, no certificate problems.

## 3. Test and deploy the chaincode

```bash
cd chaincode/evidence && go test ./...            # 22 tests against a fake ledger, no network needed
# inside WSL (Git Bash: prefix with MSYS_NO_PATHCONV=1; write the log INSIDE WSL, see section 6):
VER=1.1 SEQ=2 bash chaincode/scripts/deploy_cc.sh # vendors deps, packages, installs on both peers, approves for both orgs, commits
bash chaincode/scripts/cc_direct.sh               # drives the REAL chaincode with the peer CLI, bypassing Spring
```
A committed definition cannot be edited: any chaincode change means a new `VER` and a higher `SEQ`. The package is built by the
peers' Docker builder (`fabric-ccenv`), so `go.mod`'s `go` directive must not exceed that image's Go (currently 1.26).

## 4. Run the backend against real Fabric

Environment (a scratch `env.sh` outside the repo; never commit it): `DB_PASSWORD`, `DB_URL`, `BLOCKEVIDENCE_JWT_SECRET`,
`BLOCKEVIDENCE_DEV_SEED_PASSWORD`, `SPRING_PROFILES_ACTIVE=dev`, plus the Fabric identity as **paths** (constraint C-07):

| Variable | Value used here |
|---|---|
| `FABRIC_TLS_CERT_PATH` | `\\wsl.localhost\Ubuntu\home\debop\crime-evidence-mgmt\fabric-samples\test-network\organizations\peerOrganizations\org1.example.com\peers\peer0.org1.example.com\tls\ca.crt` |
| `FABRIC_CERT_PATH` | `...\users\User1@org1.example.com\msp\signcerts` (a directory holding one file, or a file) |
| `FABRIC_KEY_PATH` | `...\users\User1@org1.example.com\msp\keystore` |
| optional | `FABRIC_PEER_ENDPOINT` (`localhost:7051`), `FABRIC_PEER_HOST_ALIAS` (`peer0.org1.example.com`), `FABRIC_MSP_ID` (`Org1MSP`), `FABRIC_CHANNEL`, `FABRIC_CHAINCODE` (`evidence`) |

`./mvnw spring-boot:run` (default profile) then uses the real `FabricLedgerService`. With the three paths unset the app still starts,
ledger calls answer `503 LEDGER_UNAVAILABLE`, and `/actuator/health` shows the ledger as UNKNOWN. With the profile `memory-ledger`
the in-memory reference ledger is used instead. Verify: `/actuator/health` (as ADMIN) shows
`chaincode evidence answering on channel crimechannel via localhost:7051 as Org1MSP`.

## 5. Verify end to end

`scripts/live/live_phase2.sh` (whole Phase 2 checklist, either ledger) and `scripts/live/live_fabric_extra.sh` (Fabric-only
checks). See `scripts/live/README.md`. Results and verbatim logs: `docs/TEST_CHECKLIST.md` section P2-F.

## 6. Things that bit us (all real)

- **Git Bash rewrites `/mnt/...` and `/data/...` arguments.** Use `MSYS_NO_PATHCONV=1` for `wsl`/`docker exec`.
- **Log files written by `wsl.exe` to a Windows path get scrambled** (stdout and stderr keep separate offsets and overwrite each other).
  Redirect inside WSL (`wsl -- bash -c "script > /tmp/x.log 2>&1"`) and read it back with `wsl -- cat`.
- **`peer chaincode invoke` does not wait for the block unless `--waitForEvent` is given**, so an immediate query can miss the write.
- **The contract framework validates return values against a schema in which every field is required** unless tagged
  `metadata:",optional"`. Optional fields (`fileCid`, `disposal.requestedBy`, ...) must be tagged or every read of a physical
  item fails on a real peer. Unit tests with a fake stub cannot see this (regression test added).
- Windows Java reaches the WSL crypto material through the UNC path `\\wsl.localhost\Ubuntu\...`.
- Long shell heredocs sometimes fail to parse in this environment; write files with an editor tool instead.

## 7. Ledger side effects of testing (cannot be undone on a real ledger)

Test records are marked by case id: `FAB-DIRECT` (peer CLI), `FAB-WIRE` (fixtures), `FAB-LIVE-1..4` (API run). The channel went from
block 48 to 93. To discard them, recreate the network (`network.sh down` then `up createChannel`), which also discards `basic`.

## 8. A2: enroll every user BEFORE deploying the chaincode version that enforces certificate-based auth

**This order is mandatory, not a suggestion.** The chaincode version that reads `role`/`hf.EnrollmentID` from the
caller's certificate (`authorise()`, A2_IDENTITY_DESIGN.md section 3.2) refuses EVERY write from a certificate that
lacks those attributes - which is every identity that exists before this step, including the backend's own service
identity. Deploying that chaincode version before every user who needs to write is enrolled means their writes start
failing with no code change on their part. **Do not run step 4 until step 3 passes for every enabled user.**

1. **One-time, by the CA administrator:** `BE_REGISTRAR_SECRET=<a secret you choose> bash scripts/fabric/bootstrap_registrar.sh`
   (from WSL). Registers the least-privilege registrar `be-registrar` on `ca-org1` (may register only `client` identities
   carrying only the `role` attribute). Idempotent: safe to re-run, it does nothing if `be-registrar` already exists.
   The secret lives only in the operator's shell; it is never written to the repo, `application.yml`, or a file.
2. **Enroll every user:**
   `BE_REGISTRAR_SECRET=<same secret> WALLET_DIR=<path outside the repo> [WALLET_PASSPHRASE=<optional>] bash scripts/fabric/enroll_users.sh`
   (from WSL). Reads `(id, role)` for every `enabled` user from PostgreSQL, registers and enrolls each with attribute
   `role=<ROLE>:ecert` and `hf.EnrollmentID`, and writes `<WALLET_DIR>/<id>/{cert.pem,key.pem}` (0600). Idempotent per
   user (skips one who already has a wallet entry; `FORCE=1` re-enrolls everyone with a fresh certificate).
3. **Verify every enabled user has a wallet entry** before going further:
   ```bash
   docker exec be-postgres psql -U blockevidence -d blockevidence -t -A -c "select count(*) from users where enabled=true"
   ls "$WALLET_DIR" | wc -l     # must be >= the count above; each subdirectory has BOTH cert.pem and key.pem
   ```
   Point the running backend at the wallet (`FABRIC_WALLET_DIR`, and `FABRIC_WALLET_PASSPHRASE` if keys are encrypted)
   and confirm a real write from each role succeeds and `/actuator/health`'s `wallet` component is `UP` - this can be
   done safely against the CURRENT (non-enforcing) chaincode version first, since a correctly enrolled identity's
   writes succeed on either version. TEST_CHECKLIST Appendix P3-A2 is exactly this check, run 2026-09-22.
4. **Only once step 3 passes for every enabled user**, deploy the chaincode version whose `authorise()` enforces
   certificate attributes (`VER=1.3 SEQ=4 bash chaincode/scripts/deploy_cc.sh` for this project's A2 rollout). From
   this point on, an identity with no `role` certificate attribute cannot write; see `docs/ROLLBACK.md` for what
   this cannot be undone by (a chaincode version, once deployed, cannot be removed).
5. **A new user added later** (once A4/admin user management exists, out of scope for this project) must be enrolled
   with step 2 BEFORE they attempt a write, or they get `403 LEDGER_IDENTITY_MISSING`.

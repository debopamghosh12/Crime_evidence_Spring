# Testing Guide: full stack, real Fabric, cold start to smoke test

One practical reference for running and testing the whole system - backend, real Fabric, Postgres,
IPFS, and the frontend - from nothing running to a verified working demo. Written for this project's
actual dev machine (Windows host, Fabric toolchain in WSL Ubuntu); adjust paths if yours differ.
Related: `docs/FABRIC_RUNBOOK.md` (the authoritative Fabric reference this guide pulls together),
`docs/DECISIONS.md` D-060/D-061/D-071 (the CA/wallet recovery pattern in Part 5), `frontend/README.md`.

---

## Part 1 — Login credentials

**These are local development credentials only.** `DevUserSeeder` (`@Profile("dev")`) creates one user
per role automatically the first time the backend boots against an empty `users` table. They are not
real accounts, they exist only so there's something to log in with locally, and they must never be
used, exposed, or relied upon outside local testing.

| Role | Email | Password |
|---|---|---|
| COLLECTOR | `collector@blockevidence.local` | *(see below)* |
| FORENSIC_ANALYST | `forensic-analyst@blockevidence.local` | *(see below)* |
| PROSECUTOR | `prosecutor@blockevidence.local` | *(see below)* |
| JUDGE | `judge@blockevidence.local` | *(see below)* |
| AUDITOR | `auditor@blockevidence.local` | *(see below)* |
| ADMIN | `admin@blockevidence.local` | *(see below)* |

All six share **one password**, and it is **not a fixed value printed anywhere in this repo** (C-07 -
no secrets in code, config defaults, tests, *or docs*, this file included). It comes from the
`BLOCKEVIDENCE_DEV_SEED_PASSWORD` environment variable, which you set yourself in your local `.env`
(gitignored, never committed - see `.env.example`). **Check your own `.env` for the current value** -
whatever you typed there is the real password for all six accounts above. If `.env` has no value for
it, `DevUserSeeder` logs `"dev profile active but BLOCKEVIDENCE_DEV_SEED_PASSWORD is not set: no users
seeded"` and seeds nobody - there is no built-in fallback password to fall back to.

Each user's display name is `"Dev " + ROLE` (e.g. `Dev COLLECTOR`, `Dev FORENSIC_ANALYST`). Seeding is
idempotent - re-running the backend against the same database does not reset passwords or duplicate
users; changing `BLOCKEVIDENCE_DEV_SEED_PASSWORD` later does **not** change an already-seeded user's
password (only newly-created users get the new value - a real gap found and worked around live in
D-070/D-071, see Part 5 if a seeded user's password stops matching what's in `.env`).

---

## Part 2 — Startup, in order

### 2.1 Fabric network (WSL)

The Fabric toolchain lives in WSL Ubuntu at `/home/debop/crime-evidence-mgmt/fabric-samples`, **not**
on the Windows side. Per `FABRIC_RUNBOOK.md` section 2: **never** run `network.sh down` to "start
fresh" - it destroys the channel and all crypto material. Always **revive** the existing containers.

```bash
# From a WSL shell (or: wsl -d Ubuntu -- bash -c "...")
docker start ca_orderer ca_org1 ca_org2; sleep 3
docker start orderer.example.com;        sleep 3
docker start peer0.org1.example.com peer0.org2.example.com
```

Right after starting, the peers may log `SERVICE_UNAVAILABLE` for a few seconds while the orderer
elects a leader - normal, wait it out. Confirm the network is actually up and the channel is intact:

```bash
cd /home/debop/crime-evidence-mgmt
. chaincode/scripts/fab_env.sh; org1
peer channel list                                                      # expect: crimechannel
peer lifecycle chaincode querycommitted -C crimechannel                # expect: Name: evidence, Version: 1.3, Sequence: 4
```

If `ca_org1`/`ca_org2`/`ca_orderer` don't start cleanly, or `querycommitted` doesn't show the expected
chaincode, stop here and check `docker logs <container>` before going further - do not proceed to
enrolling users or starting the backend against a network that isn't actually healthy.

**If this is the first time in a while**: check whether every enabled user still has a valid wallet
entry (Part 5 covers exactly what to do if not - this is the step most likely to have bit-rotted since
the last session, per this project's own history).

### 2.2 Postgres + IPFS

**Check first whether standalone `be-postgres`/`be-ipfs` containers already exist** from earlier work
(`docker ps -a | grep -E "be-postgres|be-ipfs"`). If they do, just start those - they already have this
project's accumulated dev data (seeded users, cases, evidence) and use the exact ports
(`5433`, `5001`) this project's own scripts assume:

```bash
docker start be-postgres be-ipfs
```

**If they don't exist yet** (a genuinely fresh machine), use `docker-compose.yml`'s `postgres` and
`ipfs` services instead - but **not** its `backend` service, which is hardcoded to
`SPRING_PROFILES_ACTIVE: dev,memory-ledger` and will never use real Fabric no matter what else you do:

```bash
cp .env.example .env   # once - then fill in DB_PASSWORD, BLOCKEVIDENCE_JWT_SECRET,
                        # BLOCKEVIDENCE_ENCRYPTION_MASTER_KEY, BLOCKEVIDENCE_DEV_SEED_PASSWORD
                        # (openssl rand commands are in the file's own comments)
docker compose up -d postgres ipfs
```

**Real gotcha, found live in this project (D-071)**: `docker compose` reads and validates *every*
required variable in the whole file - including `backend`'s `BLOCKEVIDENCE_JWT_SECRET` and
`BLOCKEVIDENCE_ENCRYPTION_MASTER_KEY` - even when you only ask it to start `postgres ipfs`. A `.env`
with only `DB_PASSWORD` set will fail immediately with
`error while interpolating services.backend.environment.BLOCKEVIDENCE_JWT_SECRET: ...`. Fill in the
whole file, not just the two variables these two services actually use.

**Both routes use the same host ports** (`5433`→Postgres, `5001`→IPFS) - never run the standalone
containers and the compose services at the same time; `docker compose up` will fail to bind a port
already held by `be-postgres`/`be-ipfs`, and vice versa.

### 2.3 Spring Boot backend (pointed at real Fabric, not `memory-ledger`)

```bash
set -a && source .env && set +a
SPRING_PROFILES_ACTIVE=dev ./mvnw.cmd spring-boot:run   # Windows; drop .cmd elsewhere
```

`.env` must have, in addition to the four variables above, the real Fabric connection settings (paths
are Windows UNC paths into WSL, per `FABRIC_RUNBOOK.md` section 4):

```bash
FABRIC_TLS_CERT_PATH='\\wsl.localhost\Ubuntu\home\debop\crime-evidence-mgmt\fabric-samples\test-network\organizations\peerOrganizations\org1.example.com\peers\peer0.org1.example.com\tls\ca.crt'
FABRIC_CERT_PATH='\\wsl.localhost\Ubuntu\home\debop\crime-evidence-mgmt\fabric-samples\test-network\organizations\peerOrganizations\org1.example.com\users\User1@org1.example.com\msp\signcerts'
FABRIC_KEY_PATH='\\wsl.localhost\Ubuntu\home\debop\crime-evidence-mgmt\fabric-samples\test-network\organizations\peerOrganizations\org1.example.com\users\User1@org1.example.com\msp\keystore'
FABRIC_WALLET_DIR='<your durable wallet path - see Part 5>'
DB_URL=jdbc:postgresql://localhost:5433/blockevidence
```

**Real gotcha (values above use single quotes, not left bare)**: if you `source .env` in a bash shell
(as the command above does), unquoted backslashes are silently swallowed as shell escape characters -
a UNC path like `\\wsl.localhost\Ubuntu\...` written *without* quotes comes out the other side as
`wsl.localhostUbuntu...`, every separator gone, and the backend fails with a plain
`java.nio.file.NoSuchFileException` that gives no hint the value was ever mangled (found live, D-071).
Always wrap these four path values in single quotes in `.env`.

**Confirm it's actually using real Fabric, not `memory-ledger`** - this is the single most important
check in this whole guide, since the app boots and answers requests either way with no visible
difference until you check:

```bash
# Log in as ADMIN first - health detail is gated to ADMIN (management.endpoint.health.show-details)
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"email":"admin@blockevidence.local","password":"<your BLOCKEVIDENCE_DEV_SEED_PASSWORD>"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")

curl -s http://localhost:8080/actuator/health -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

Look at the `ledger` component specifically:
- **Real Fabric (what you want)**: `"detail": "chaincode evidence answering on channel crimechannel via localhost:7051 as Org1MSP"`, status `UP`.
- **`memory-ledger` (wrong for a demo)**: `"detail": "IN-MEMORY reference ledger (dev/test only). NOT a blockchain, NOT tamper-proof and loses all data on restart."`. If you see this, `SPRING_PROFILES_ACTIVE` still has `memory-ledger` in it somewhere - remove it and restart.
- **Fabric unconfigured** (one of the three `FABRIC_*_PATH` variables is blank or unreadable):
  `"detail": "Cannot set up the Fabric connection"`, status `DOWN`. Check the paths, and check that
  section 2.1's containers are actually running.

### 2.4 Frontend

```bash
cd frontend
cp .env.local.example .env.local   # once, if it doesn't already exist
npm install                        # once, or after a dependency change
npm run dev                        # http://localhost:3000
```

Confirm `frontend/.env.local` has:
```
NEXT_PUBLIC_API_URL=http://localhost:8080
```
Never `:3000` (that's this same Next.js dev server) or `:3001` (a different project's Express backend
that doesn't exist here - a leftover from the source repo this frontend was built from, D-069).

### 2.5 Smoke test

1. Open `http://localhost:3000/login`, sign in as `collector@blockevidence.local` with your seed
   password (Part 1).
2. **Evidence → Log New Item.** A case must already exist first - if you don't have one, log in as
   `prosecutor@blockevidence.local` and create one from **Cases → New Case** (needs a case number,
   title, and a lead officer's user ID - any seeded user's UUID, visible via `GET /api/auth/me` while
   logged in as them). Back as collector: pick `DIGITAL`, attach any small real file, submit.
3. You should land on the new item's detail page showing a real `Metadata SHA-256` and `File SHA-256`.
   Click **Verify Integrity** - expect `VERIFIED` on both file and metadata, with `expected`/`actual`
   hashes matching exactly.
4. Go back to **Evidence** (the list) and confirm the item you just registered appears in the table.

If all four steps work, you have a genuinely working, Fabric-backed round trip: real chaincode write,
real IPFS pin, real hash comparison against the real ledger. If step 3 shows `NOT_FOUND` or an error
instead of `VERIFIED`, check IPFS (`docker exec be-ipfs ipfs id` should succeed) before assuming
anything is wrong with the evidence-registration code itself.

---

## Part 3 — Cold-start checklist (copy-paste order)

```bash
# 1. Fabric (WSL)
wsl -d Ubuntu -- bash -c "docker start ca_orderer ca_org1 ca_org2 orderer.example.com peer0.org1.example.com peer0.org2.example.com"

# 2. Postgres + IPFS (pick ONE)
docker start be-postgres be-ipfs
#   -- or, on a fresh machine --
# docker compose up -d postgres ipfs

# 3. Backend (from the project root, real Fabric)
set -a && source .env && set +a
SPRING_PROFILES_ACTIVE=dev ./mvnw.cmd spring-boot:run

# 4. Frontend (separate terminal)
cd frontend && npm run dev
```

Then confirm the ledger health check (section 2.3) before demoing anything.

---

## Part 4 — Tearing down cleanly

```bash
# Frontend: Ctrl+C in its terminal

# Backend: Ctrl+C in its terminal
#   (or, if it was launched detached: find the java.exe PID and `taskkill //F //PID <pid>`)

# Postgres + IPFS
docker stop be-postgres be-ipfs
#   -- or, if you used compose --
# docker compose stop postgres ipfs      # keeps data; use `down` (no -v) to remove containers,
                                          # never `-v` unless you want to lose the Postgres volume

# Fabric (WSL) - STOP only, never `network.sh down` (destroys the channel and all crypto material)
wsl -d Ubuntu -- bash -c "docker stop peer0.org1.example.com peer0.org2.example.com orderer.example.com ca_org1 ca_org2 ca_orderer"
```

Nothing here deletes data. `docker compose down -v` and `network.sh down` are the only two commands in
this whole project that actually destroy state - neither is part of a normal teardown.

---

## Part 5 — If the Fabric CA registrar secret (or a user's wallet) is missing

This project has hit this exact class of problem twice already (D-060/D-061, D-071) - the pattern is
always the same: `BE_REGISTRAR_SECRET` or the enrolled-user wallet was never given a **durable** home,
so it got lost between sessions. Fix it properly this time rather than repeating the recovery a third
time: pick **one durable wallet directory now** (recommended: `/home/debop/blockevidence-wallet` inside
WSL - `enroll_users.sh` already runs from WSL, so this needs no Windows/WSL path translation at all)
and set `FABRIC_WALLET_DIR` to it (as its Windows UNC equivalent,
`\\wsl.localhost\Ubuntu\home\debop\blockevidence-wallet`, in `.env`) going forward. **Never** point it
at a temp/scratch directory again.

### 5a. The registrar secret (`be-registrar`) is lost
`bootstrap_registrar.sh` says this itself when re-run: it's idempotent and will tell you `be-registrar`
already exists rather than erroring. Reissue its secret as the CA bootstrap admin:

```bash
# From WSL, inside fabric-samples' test-network setup:
FS=/home/debop/crime-evidence-mgmt/fabric-samples
CAD=$FS/test-network/organizations/fabric-ca/org1
TLS=$CAD/ca-cert.pem
BOOT=$(docker inspect ca_org1 --format '{{index .Config.Cmd 2}}' | sed -n 's/.*-b \([^ ]*\) .*/\1/p')
W=$(mktemp -d)
FABRIC_CA_CLIENT_HOME=$W/admin fabric-ca-client enroll -u "https://$BOOT@localhost:7054" --caname ca-org1 --tls.certfiles "$TLS"

NEW_SECRET=$(openssl rand -hex 16)
FABRIC_CA_CLIENT_HOME=$W/admin fabric-ca-client identity modify be-registrar --secret "$NEW_SECRET" \
  --caname ca-org1 --tls.certfiles "$TLS"
echo "New BE_REGISTRAR_SECRET: $NEW_SECRET"   # use this value below, then forget it - never write it to a file
rm -rf "$W"
```
Then re-run `enroll_users.sh` with that new secret (5b) - it's idempotent per user, so this is safe to
run even if most users are already enrolled.

### 5b. A user has no wallet entry, or the wallet directory itself is gone
```bash
# From WSL:
BE_REGISTRAR_SECRET=<the current secret> WALLET_DIR=/home/debop/blockevidence-wallet \
  bash scripts/fabric/enroll_users.sh
```
This reads every `enabled` user from Postgres and enrolls whichever ones don't already have a wallet
entry at `WALLET_DIR` - safe to re-run at any time. Verify before trusting it:
```bash
docker exec be-postgres psql -U blockevidence -d blockevidence -t -A -c "select count(*) from users where enabled=true"
ls /home/debop/blockevidence-wallet | wc -l   # must be >= the count above
```

### 5c. A specific already-registered user's secret was consumed and their wallet is gone
(`enroll_users.sh` alone won't fix this - `--id.maxenrollments 1` means their original one-time secret
is already spent, and re-registering the same user id is rejected as a duplicate.) Reissue that one
user's secret the same way as 5a, using `identity modify <user-id> --secret <new> --maxenrollments -1`
(not `1` - resetting `maxenrollments` back to `1` does **not** reset the CA's internal
already-enrolled counter for an identity that already used its one enrollment; `-1` sidesteps that,
D-061), then enroll with the new secret and copy `cert.pem`/`key.pem` into
`$WALLET_DIR/<user-id>/` by hand (or adapt the loop body inside `enroll_users.sh` for a single id).

### 5d. Dev users' database passwords stopped matching `BLOCKEVIDENCE_DEV_SEED_PASSWORD`
Not a Fabric problem, but the same "session drift" class of issue, and it looks like an auth failure
first: `DevUserSeeder` only sets a password when a user is *created* - it never resets one on an
existing row, even if `BLOCKEVIDENCE_DEV_SEED_PASSWORD` in `.env` changes later. If login fails with
`401` for a seeded user despite using what `.env` says is the password, their stored hash predates the
current value. Fix by resetting it directly (or drop and re-seed the `users` table if there's no other
data worth keeping):
```bash
# Generate a BCrypt hash of your current BLOCKEVIDENCE_DEV_SEED_PASSWORD (needs spring-security-crypto
# on the classpath - see DECISIONS D-071 for the exact jshell invocation used here), then:
docker exec be-postgres psql -U blockevidence -d blockevidence \
  -c "UPDATE users SET password_hash='<the new bcrypt hash>' WHERE email LIKE '%@blockevidence.local';"
```

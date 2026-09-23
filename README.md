# BlockEvidence

Spring Boot rewrite of a Hyperledger Fabric evidence-management backend (final-year project), plus a
Next.js frontend. Real chain-of-custody, envelope-encrypted content, and a real (not simulated) Fabric
ledger underneath - see `docs/HANDOVER.md` for the full end-of-project summary and
`docs/FEATURE_LIST.md` for what was actually built.

**Repo layout**: `src/` (Spring Boot backend), `chaincode/` (the Go chaincode, `evidence`), `frontend/`
(Next.js UI), `docs/` (everything - architecture, decisions, constraints, the full session history),
`docker-compose.yml` + `Dockerfile` (backend/Postgres/IPFS demo stack), `scripts/fabric/` (Fabric
operator scripts).

## For teammates: getting started

**Read this before you clone-and-run** - what you get automatically and what you have to set up
yourself are very different things here, by design (`docs/CONSTRAINTS.md` C-07: no secrets, ever, in
this repo - not in code, not in config defaults, not in docs). Cloning this repo gets you the
**application code** for everything below. It does **not** get you a working `.env`, and for Tier 2 it
does **not** get you a Fabric network or wallet - both of those you build yourself, on your own
machine, every time.

### Tier 1 — Quick local try (no Fabric, ~10 minutes)

This is for trying the app - clicking through the UI, registering evidence, seeing encryption and
custody transfer work - **not** for anything you'd cite as "tested against Fabric." The ledger backing
it is `memory-ledger`: a plain in-JVM data structure, not a blockchain, with no persistence of its own
(a backend restart loses every evidence record; Postgres-native data like users/cases survives).

```bash
cp .env.example .env
# fill in real values - the file's own comments give you the exact `openssl rand` command for each:
#   DB_PASSWORD, BLOCKEVIDENCE_JWT_SECRET, BLOCKEVIDENCE_ENCRYPTION_MASTER_KEY
# BLOCKEVIDENCE_DEV_SEED_PASSWORD is optional - set it to log in as the seeded dev users
docker compose up --build
```

This one command starts backend + Postgres + IPFS together, with `SPRING_PROFILES_ACTIVE:
dev,memory-ledger` already set for you in `docker-compose.yml` - the backend answers on
`http://localhost:8080` once `/actuator/health` shows everything `UP` (a genuine cold build - first run,
no cached image layers - took 3m31s when last measured, `docs/TEST_CHECKLIST.md` P5-L2L3; a warm
restart is much faster).

Then, in a separate terminal, the frontend (this is **not** part of the `docker compose` stack - run it
yourself, separately):

```bash
cd frontend
cp .env.local.example .env.local   # NEXT_PUBLIC_API_URL=http://localhost:8080 by default - correct as-is
npm install
npm run dev                        # http://localhost:3000
```

Log in with any of the seeded dev users (`collector@blockevidence.local`, `judge@blockevidence.local`,
etc. - see `TESTING_GUIDE.md` Part 1 for the full list) and the password you set as
`BLOCKEVIDENCE_DEV_SEED_PASSWORD`.

**What you will NOT be testing this way**: real Fabric consensus, real transaction IDs, real per-user
CA-issued identities (A2), anything about how the actual blockchain layer behaves under a real network.
Everything ledger-related is a plain in-memory stand-in. Good for "does the app work"; not evidence for
"does the blockchain work."

### Tier 2 — Full stack with real Fabric

**This is manual, and it is not part of `docker compose` - nothing about it starts automatically, ever,
no matter what you run.** `docs/DECISIONS.md` D-067 explains why Fabric was deliberately kept out of the
Compose stack (a real risk assessment, not an oversight): reproducing Fabric's own multi-stage,
retry-looped bootstrap reliably inside Compose was judged not worth the risk for this project, so it
stays a documented, separate, manual step.

**Real prerequisites, before you attempt any of this**:
- **WSL2 (Windows) or native Linux** - Fabric's own tooling (`fabric-samples`) is written for and
  tested on Linux. This project's own development happened via WSL2 Ubuntu on a Windows host; native
  Linux works too and needs less path-translation fuss (see `docs/DEPLOYMENT_DESIGN.md` section 4.1).
- **Docker** (with enough resources allocated - a full Fabric test network is 3 CAs + 1 orderer + 2
  peers + chaincode containers, on top of whatever else you're already running).
- **The Fabric binaries and `fabric-samples`** (`peer`, `fabric-ca-client`, `configtxgen`, etc.) -
  these are not part of this repo, do not come from `npm install` or `mvn`, and must be obtained and
  set up separately following Hyperledger Fabric's own installation instructions.
- **Go** (to build/test the chaincode in `chaincode/evidence/`) and a registered CA registrar identity
  + per-user wallet (see below) - none of this ships in the repo either.

**Once those exist**, follow, in order:
1. `docs/FABRIC_RUNBOOK.md` - the authoritative reference for bringing the network up, deploying the
   chaincode, and the mandatory A2 user-enrollment sequence (enroll every user *before* deploying the
   chaincode version that enforces certificate-based auth, not after).
2. `TESTING_GUIDE.md` - a single copy-paste cold-start checklist covering the whole stack (Fabric +
   Postgres/IPFS + backend + frontend) plus a smoke test, and what to do if a CA registrar secret or a
   user's wallet goes missing (it has happened to this project more than once - the guide covers the
   real recovery pattern, not a guess).

**Nothing about your Fabric network, wallet, or CA credentials is provided by cloning this repo** - you
register your own CA registrar, enroll your own users, and hold your own wallet, on your own machine.
That is deliberate (C-07 again: no credentials of any kind belong in this repo), not something missing
from the setup.

---

For everything else - architecture, every design decision and why, what's a known limitation vs. future
work, and where the evidence is for each feature's own claim - start at `docs/HANDOVER.md`.

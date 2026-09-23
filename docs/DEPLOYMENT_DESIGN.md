# Deployment design: a real, reachable demo (not production, not watered down)

> **Status: DRAFT FOR APPROVAL. Nothing described here is implemented, and nothing already built or
> deployed locally has been touched.** Per the project's own pattern (`A2_IDENTITY_DESIGN.md`,
> `F2_F3_ENVELOPE_ENCRYPTION_DESIGN.md`): this is shown before any infrastructure work, with the exact
> things I'd need decided listed in section 5.
> Related: `docs/CONSTRAINTS.md` C-07/C-08/C-09/C-10, `docs/FABRIC_RUNBOOK.md`, `docker-compose.yml`,
> `docs/DECISIONS.md` D-067 (why Fabric was kept off the local Compose stack) and D-071 (the real
> CA/wallet recovery this project has already been through twice).

## 0. What "real, not watered down" means here, precisely

The explicit ask is: real Fabric (not `memory-ledger`), reachable over the internet, good enough to
link and demo for a viva - not a production system, not multi-region, not hardened against a
sophisticated attacker. That framing matters for every decision below: I am optimizing for "this
genuinely runs the whole stack, on the internet, and doesn't lie about what's protecting it," not for
production-grade resilience or security. Every accepted risk from local development (C-08, C-09, C-10)
stays exactly as accepted - moving to a VM does not fix any of them, and section 3 says so plainly
rather than implying otherwise by omission.

## 1. Target architecture

```
                                   Internet
                                       |
                          ┌────────────┴────────────┐
                          │                          │
                    Vercel (frontend)          <domain>.example
                    Next.js, static/edge             |
                          |                    Caddy or nginx
                          |                    (reverse proxy, TLS)
                          |                          |
                          └──────── HTTPS ───────────┤
                                                      |
                                          ┌───────────┴───────────┐
                                          │   Single Linux VM     │
                                          │   (8 GB RAM assumed)  │
                                          │                       │
                                          │  docker compose:      │
                                          │   - backend (:8080,   │
                                          │     proxied only)     │
                                          │   - postgres          │
                                          │   - ipfs (--offline)  │
                                          │                       │
                                          │  Fabric (native       │
                                          │  Docker on the same   │
                                          │  VM, adapted from     │
                                          │  the WSL scripts):    │
                                          │   - 3 CAs             │
                                          │   - orderer           │
                                          │   - 2 peers           │
                                          │   - chaincode         │
                                          │     containers        │
                                          └───────────────────────┘
```

- **VM**: one Linux host (Ubuntu 22.04/24.04 LTS assumed - matches `fabric-samples`' own tested
  platform far better than any other distro choice would). Docker + Docker Compose installed directly
  on Linux - no WSL layer, no Windows/Linux boundary to cross. This is actually a **simplification**
  relative to local dev, not an added complication (section 4 explains why).
- **Backend + Postgres + IPFS**: the existing `docker-compose.yml`, unchanged in shape, run on the VM.
  `SPRING_PROFILES_ACTIVE=dev` only - **`memory-ledger` must never be in the deployed profile list**
  (section 2.4).
- **Fabric**: `fabric-samples/test-network` brought up the same way `FABRIC_RUNBOOK.md` section 2-3
  already does, but run directly on the VM's own Linux Docker (not inside WSL) - so every path in
  section 4 (`FABRIC_TLS_CERT_PATH` etc.) becomes a plain Linux filesystem path, no
  `\\wsl.localhost\Ubuntu\...` UNC-path translation at all. The chaincode, channel, and A2 enrollment
  steps (`FABRIC_RUNBOOK.md` section 8) are identical in sequence; only the host paths change.
- **Frontend**: deployed to Vercel from the `frontend/` directory, `NEXT_PUBLIC_API_URL` pointing at
  the VM's public HTTPS endpoint (not `localhost:8080` - the whole reason this frontend integration
  used one shared `frontend/src/lib/api.ts` config point, D-069, was exactly so this is a one-line
  change). Vercel's own CDN/edge handles the frontend's own TLS; nothing new to design there.
- **Reverse proxy**: Caddy or nginx on the VM in front of the backend only (Fabric's own ports -
  `7051`/`7054`/orderer - are **never** exposed publicly; only `8080`, proxied, is). Caddy's automatic
  Let's Encrypt is the simpler default for a single-VM, single-domain demo; nginx is the fallback if
  Caddy's automatic-HTTPS assumptions (a real DNS A record already pointing at the VM before first
  request) don't fit how the domain gets provisioned. Either way: the backend container's `8080` binds
  to `127.0.0.1` only, not `0.0.0.0`, so it is unreachable except through the proxy.

## 2. What changes from the current local setup

### 2.1 Secrets handling
Today, local secrets live in `.env` (gitignored) or a shell environment sourced before `mvnw
spring-boot:run` - fine for a single developer's machine, never committed (`.env.example` already
establishes this pattern, C-07). On a VM this becomes:
- A single `.env` file on the VM itself (root-owned, `chmod 600`, outside any web-served directory),
  populated once at provisioning from a password manager or `openssl rand`, matching
  `.env.example`'s own generation comments exactly.
- The Fabric identity paths (`FABRIC_TLS_CERT_PATH`, `FABRIC_CERT_PATH`, `FABRIC_KEY_PATH`,
  `FABRIC_WALLET_DIR`) point at a directory on the VM's own filesystem, also outside the repo checkout
  and outside anything the web server can reach (C-07's existing rule, just now enforced by VM
  filesystem layout instead of "don't put it in the WSL/Windows path I happen to be using today").
- **No new secret-handling mechanism** (no Vault, no cloud KMS) - that would be real infrastructure
  work beyond "a genuine, working demo," and this project's own scale doesn't need it. Flagged as a
  explicit non-goal, not an oversight.

### 2.2 CORS allow-list
`CorsProperties.allowedOrigins` (`SecurityConfig`, D-069) currently defaults to
`http://localhost:3000`. Deployment sets `BLOCKEVIDENCE_CORS_ALLOWED_ORIGINS` to the real Vercel
production URL (and its preview-deployment pattern, if Vercel preview builds should also reach the
live backend - open question 5.3). No code change; this property was built configurable for exactly
this reason.

### 2.3 Reverse proxy + HTTPS
New: a `Caddyfile` (or nginx site config) added to the repo under something like `deploy/` - not
touching `docker-compose.yml`'s existing three services, but adding the proxy as a fourth service (or
running it outside Compose, directly on the VM - open question 5.4) in front of `backend:8080`.
Caddy's config for this shape is genuinely small (reverse_proxy + automatic HTTPS is close to Caddy's
own quickstart example), which is most of why it's the default recommendation over nginx here.

### 2.4 Startup guard: refuse to boot on `memory-ledger`
**New, not built yet.** Today (`FabricProperties.isConfigured()`'s own doc comment) the app is
*designed* to start even with Fabric completely unconfigured - ledger calls just answer `503`, which is
the right behavior for local dev (nobody wants Fabric mandatory to run a unit test). That same
permissiveness is exactly wrong for a public deployment that's supposed to demonstrate a real
blockchain: a misconfigured deploy that silently included `memory-ledger` in
`SPRING_PROFILES_ACTIVE` would boot fine, answer every request, and simply not be backed by Fabric at
all - reachable on the internet, looking correct, while quietly being the reference ledger. This
project already has `InMemoryLedgerService`/`FabricLedgerService` cleanly split by
`@Profile("memory-ledger")`/`@Profile("!memory-ledger")`; the guard doesn't change that, it adds a
check *on top*.

Design: a small `@Profile("memory-ledger")` `ApplicationRunner` (or a startup listener) that checks a
new environment flag - e.g. `BLOCKEVIDENCE_REQUIRE_REAL_LEDGER=true` - and, if set, throws before the
context finishes starting (a clear log line + non-zero exit, not a silent 503 later). The VM's own
`docker-compose.yml`/systemd unit always sets that flag; local dev and CI never do, so nothing about
today's `L2`/`L3` test setup (which deliberately uses `memory-ledger`) changes. This is deliberately
the *opposite* of a new Spring profile named e.g. `prod` gating the guard - profiles already select
which `LedgerService` bean exists; this guard is about catching the *mistake* of the wrong profile
combination reaching a deployed environment, which needs its own signal, not another profile that
could itself be forgotten.

## 3. What does NOT change (stated plainly, not implied away by moving to a VM)

- **C-09 (IPFS `--offline`)**: unchanged. The VM's `ipfs` container runs `daemon --offline`, identical
  to `docker-compose.yml` today. A public IP address in front of the backend does not change anything
  about the IPFS node's own networking - it was never planned to join the public swarm and still won't.
- **C-08 (backend custodies Fabric signing keys)**: unchanged and unchanged *by design*. A2 already
  states this residual explicitly ("the backend still custodies every user's private key... a
  compromised backend host can still act as any enrolled user"). Putting that backend on a VM reachable
  from the internet does not add a new trust boundary to fix and does not remove the existing one - it
  just means the same already-accepted risk is now reachable over HTTPS instead of only from
  `localhost`. No mitigation beyond what A2 already has (least-privilege registrar, offline wallet
  storage) is proposed here.
- **C-10 (server-held master key for F2/F3)**: unchanged for the same reason. No HSM, no key rotation,
  no split-custody scheme - explicitly out of scope per C-10's own text, and this deployment doesn't
  revisit that decision.
- **The chain-of-custody/audit/disposal *design*** - nothing about how evidence, custody, or disposal
  work changes. This is infrastructure only.

**One thing that *does* get a little worse, worth naming rather than leaving implicit**: on
`localhost`, an attacker needs local access to the machine to reach any of the above. On a public VM,
the same trust model (backend custodies keys) is reachable by anyone on the internet who can guess or
find credentials. The trust model is identical; the exposure surface is not. This is the tradeoff of
"reachable for a demo" and is accepted as part of the ask, not hidden.

## 4. Real unknowns (flagged, not guessed past)

### 4.1 Does the Fabric bootstrap need real script changes for a fresh Linux VM?
**Mostly no, and where it does, it's a simplification, not new risk** - but this is asserted from
reading the scripts, not from having actually run them on a fresh Linux VM, which I have not done.
Specifically:
- `fabric-samples/test-network/network.sh` is written for and tested on native Linux/macOS Docker -
  the WSL detour in this project existed only because development happened on a Windows machine, not
  because Fabric itself needs WSL. A native Linux VM runs it the way its own documentation assumes.
- Every place this project's own runbook currently threads a Windows-to-WSL path (the
  `\\wsl.localhost\Ubuntu\...` UNC paths in `FABRIC_RUNBOOK.md` section 4, the `MSYS_NO_PATHCONV=1`
  Git Bash workaround in section 6, the "logs written by `wsl.exe` get scrambled" issue) simply doesn't
  exist on a VM where the JVM and Fabric are both plain Linux processes talking to a plain Linux
  filesystem. `FABRIC_TLS_CERT_PATH` etc. become ordinary absolute paths.
- **What I have not verified**: `chaincode/scripts/deploy_cc.sh` and `chaincode/scripts/cc_direct.sh`
  were written and only ever run from inside WSL Ubuntu against this project's specific
  `fabric-samples` layout. I have read them and see nothing WSL-specific in their own logic (they call
  `peer`/`fabric-ca-client` binaries and expect the same directory layout `network.sh` produces
  regardless of host OS) - but "should work unchanged" is a reasoned expectation from reading, not a
  live-tested fact, and this project's own history (D-032's `optional` schema-tag bug, D-061's
  `maxenrollments` quirk) is a specific reminder that Fabric's real behavior has repeatedly surprised
  this project even when the design looked right on paper. **I'd want to actually run
  `network.sh up createChannel` and the chaincode deploy on a real, disposable Linux VM before
  calling any of this confirmed** - open question 5.1.

### 4.2 Realistic VM sizing
No load testing or memory profiling of this stack has been done at any point in this project (KNOWN_GAPS
already lists load/concurrent-upload capacity as untested). The 8 GB figure in the user's own framing is
a reasonable *starting* assumption, not a validated one:
- Fabric's own reference footprint for a 2-peer/1-orderer/3-CA test network is commonly quoted around
  2-4 GB resident across all its containers at idle (from Fabric's own published guidance, not this
  project's measurement).
- Backend (JVM) + Postgres + IPFS + a reverse proxy adds a further 1-2 GB at idle, generously, again
  not measured here.
- That leaves a thin margin on 8 GB once OS overhead and Docker's own bookkeeping are included, with
  **zero headroom validated for concurrent demo traffic** (a professor and classmates clicking through
  during a viva, say). **Real recommendation: provision 8 GB as the floor and watch `docker stats`
  during the very first live rehearsal before trusting it for the actual demo** - not asserted as
  sufflcient. If it's tight, the cheapest fix is 16 GB, not architecture changes.

### 4.3 Will the CA/registrar credential problems recur?
**Likely mechanism identified, but genuinely open** - this project hit the same class of problem twice
already (D-060/D-061's registrar-secret loss, D-071's from-scratch wallet reconstruction from a session
scratchpad directory), and both times the root cause was the same: `BE_REGISTRAR_SECRET` and
`WALLET_DIR` were **never given a durable home** - the registrar secret "lives only in the operator's
shell" by design (`FABRIC_RUNBOOK.md` section 8, itself a deliberate no-standing-credential choice,
A2-Q6), and the wallet ended up in a session-scoped scratchpad directory purely by circumstance, not by
design.

On a VM, this recurs **if and only if** the deployment process repeats that same pattern - if
`BE_REGISTRAR_SECRET` is typed into an interactive shell and never written anywhere durable, and
`WALLET_DIR` is left in some path tied to whichever tool/session provisioned it rather than a fixed,
documented, backed-up location on the VM itself. It does **not** recur if provisioning treats these as
the durable secrets they actually are: the registrar secret saved in the VM's own `.env` (per 2.1) or a
password manager (never the repo), and `WALLET_DIR` fixed at a real path like `/opt/blockevidence/wallet`
that's part of the VM's backup story, not a temp directory. **This is a process decision to get right at
provisioning time, not a Fabric bug to route around** - flagged so it's decided deliberately (open
question 5.2) rather than repeated a third time.

## 5. Decisions I need from you

| # | Question | My default |
|---|---|---|
| DEPLOY-Q1 | Rehearse the Fabric bootstrap on a real, disposable Linux VM (or a Linux Docker VM locally) before trusting section 4.1's "should work unchanged" for the actual demo box | **Yes** |
| DEPLOY-Q2 | Registrar secret and `WALLET_DIR` get a durable home at provisioning (VM's own `.env` + a fixed `/opt/...` path), documented in a new `FABRIC_RUNBOOK.md` "VM" section, so the credential-loss pattern from D-060/D-061/D-071 can't repeat by accident | **Yes** |
| DEPLOY-Q3 | Caddy (automatic Let's Encrypt) over nginx + certbot, unless DNS provisioning order makes Caddy's automatic-HTTPS assumption awkward | **Caddy** |
| DEPLOY-Q4 | Reverse proxy as a fourth `docker-compose.yml` service vs. installed directly on the VM outside Compose | **Your call - no strong reason either way; Compose keeps everything in one `up`, host-installed is easier to debug independently** |
| DEPLOY-Q5 | Startup guard: new `BLOCKEVIDENCE_REQUIRE_REAL_LEDGER` env flag (section 2.4) vs. a different mechanism you'd prefer | **Yes, the env-flag design in 2.4** |
| DEPLOY-Q6 | Should Vercel preview-deployment URLs (not just the production URL) also be allowed through CORS, or does the demo only ever need the one production frontend URL | **Production URL only, to start** |
| DEPLOY-Q7 | VM provider/size - no preference stated by you yet; 8 GB / 4 vCPU Ubuntu 22.04 LTS as a starting point, upsized after DEPLOY-Q1's rehearsal if needed | **8 GB / 4 vCPU Ubuntu 22.04 LTS, revisit after rehearsal** |
| DEPLOY-Q8 | Domain: do you have one already, or does this need a placeholder (e.g. a cloud provider's own subdomain) for the demo | **Need your answer - no default** |

Nothing above is built. On approval, the next step would be DEPLOY-Q1's rehearsal (a disposable VM,
Fabric bootstrap only, no backend/frontend yet) before committing to the rest - same
"prove the risky part first" pattern as L2/L3's own Fabric-in-Compose risk assessment (D-067).

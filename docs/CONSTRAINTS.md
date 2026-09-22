# Constraints

Hard rules. Never violate an entry; if a task seems to require it, stop and ask instead of working
around it silently. The first four restate the hard constraints in `CLAUDE.md`; C-05 to C-07 were added
by the project owner on 2026-09-21.

## C-01 — Ledger calls only through `LedgerService`
Never call Fabric directly from a controller or any other service. Only the `ledger/` package may import
Fabric types. *Why:* the `LedgerService` interface (G1) is what lets a web3j/Polygon implementation be
added later without touching controllers.

## C-02 — Never delete evidence records
Archive or dispose only, with a reason and approval (FEATURE_LIST.md B5). No delete endpoint, no
`DELETE` repository method on evidence. *Why:* the tamper-proof claim fails if a record can disappear.

## C-03 — Stay inside the current phase's scope
Never touch a module outside the current phase without flagging it first (phases: FEATURE_LIST.md,
"Suggested build order").

## C-04 — No new dependency without asking
Ask first, then log it in `docs/DECISIONS.md` (chose / rejected / why).

## C-05 — Officer identity comes from the JWT, never the request body
Whoever is acting (collector, sender, approver) is read from the authenticated principal
(`AuthenticatedUser`). No request DTO may carry a field that names the acting user. *Why:* a
client-supplied officer can be forged, which would make the custody record meaningless (FEATURE_LIST.md A2).

## C-06 — No personal data on the ledger
The ledger holds only IDs, hashes and CIDs. Personal data stays off-chain. *Why:* ledger entries cannot
be erased once written (FEATURE_LIST.md F4).

## C-07 — No secrets in code
No passwords, signing keys, tokens or credentials in source, `application*.yml` defaults, tests or docs.
They come from environment variables (FEATURE_LIST.md K3). The app must refuse to start without the JWT
signing secret rather than fall back to a built-in one.

## C-08 — The chaincode authenticates user and role from a CA-issued certificate; the backend custodies user keys (REVISED, NOT CLOSED)
As of A2 (deployed 2026-09-22, `evidence` v1.3): the chaincode's `authorise()` reads `role` and `hf.EnrollmentID`
from the caller's Fabric CA-issued certificate, not from an argument alone. Every write is signed with the ACTING
USER's own enrolled identity (`FabricLedgerService`, per-user `Gateway`, `IdentityStore`); the `actorId`/`actorRole`
arguments are kept and must equal the certificate, so a bug or forged value supplying the wrong actor is refused on
the ledger, and `qscc GetTransactionByID` independently shows who really signed each write.

**This closes the specific hole C-08 originally named** (a bug or malicious value in the Spring layer claiming any
role) **but does not make the chaincode's role check independent of the backend host.** The backend still custodies
every user's private key in a wallet it reads (`FileWalletIdentityStore`); a compromised backend host can still sign
as any enrolled user, exactly as a compromised backend could act as any role before A2. Do not describe this as
"nothing can forge a user's identity" - describe it as "the chaincode can no longer be told the wrong actor by a bug
or a forged argument; a compromised backend host is a different, still-open risk." Registrar credentials (offline,
least-privilege, never held by the running app) and peer-side certificate revocation (no channel CRL configured)
are further residuals, listed in `docs/A2_IDENTITY_DESIGN.md` section 4 and `docs/KNOWN_GAPS.md`.
Approved by the project owner 2026-09-22 (A2-Q1..Q8, this exact wording is A2-Q7).

## C-09 — Never run the IPFS node outside `--offline` mode with real or realistic evidence data
A default Kubo node joins the public IPFS network and serves anything pinned on it to anyone who learns the
CID; CIDs are stored on the ledger and returned by the API. Development and demos run
`ipfs/kubo daemon --offline`. Networked or production use requires a private swarm and/or envelope encryption
(F2) first. *Why:* see DECISIONS D-020, found by observing a default node fetch data from strangers.

## C-10 — The server-held master key is a single point of compromise for all content keys (NOT CLOSED)
As of F2/F3 (`docs/F2_F3_ENVELOPE_ENCRYPTION_DESIGN.md`): every user's per-user private key is stored encrypted
at rest under one AES-256 master key, held only in the running application's configuration
(`BLOCKEVIDENCE_ENCRYPTION_MASTER_KEY`). Whoever holds that master key can decrypt every user's private key, and
therefore unwrap every evidence content key for every user, on every evidence item, past and future. Its
exposure defeats F2/F3 entirely - it is not a residual on top of envelope encryption, it is the same trust
boundary C-08 already accepts for Fabric identity keys, now extended to content keys. No HSM, no key
rotation, and no split-custody scheme (e.g. Shamir sharing of the master key) are in scope. Approved by the
project owner 2026-09-22 alongside the F2/F3 design approval.

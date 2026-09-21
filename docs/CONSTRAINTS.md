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

## C-08 — The chaincode trusts the backend-supplied role until A2 lands (KNOWN GAP, NOT RESOLVED)
Until per-user Fabric identities (A2, Phase 3) exist, the Spring backend connects with ONE application
identity and passes `actorId`/`actorRole` to the chaincode as arguments, which the chaincode checks against
its ACL table but cannot independently authenticate. This protects against bugs in the Spring layer and gives
an immutable record of who acted; it does **not** protect against a compromised or malicious backend, which
could claim any role. Do not describe the chaincode role check as authentication, in code, docs or the report.
Closing the gap is A2: users enrolled through Fabric CA with a `role` certificate attribute, read via the
chaincode's single `resolveActor` function (CHAINCODE_DESIGN.md section 5). Approved by the project owner
2026-09-22 (decision G5).

## C-09 — Never run the IPFS node outside `--offline` mode with real or realistic evidence data
A default Kubo node joins the public IPFS network and serves anything pinned on it to anyone who learns the
CID; CIDs are stored on the ledger and returned by the API. Development and demos run
`ipfs/kubo daemon --offline`. Networked or production use requires a private swarm and/or envelope encryption
(F2) first. *Why:* see DECISIONS D-020, found by observing a default node fetch data from strangers.

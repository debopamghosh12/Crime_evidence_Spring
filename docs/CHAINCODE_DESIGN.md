# Chaincode design (G2): APPROVED

> **Status: APPROVED by the project owner 2026-09-22 (G1-G7 as proposed, plus C-08 and C-09 in CONSTRAINTS.md). Implementation follows this document.**
> The Spring side of the ledger boundary
> (`LedgerService` interface, `InMemoryLedgerService`) is built to this design so the Fabric
> implementation is a swap, not a rewrite.

Feature IDs refer to `docs/FEATURE_LIST.md`. Section 9 lists the decisions (all approved as proposed).

## 1. Facts about the environment (checked 2026-09-22, not assumed)

| Fact | Evidence | Consequence |
|---|---|---|
| Fabric **2.5.15** (peer, orderer, ccenv), CA 1.5.17 | `docker images` | Peer has the **Gateway service**, so the modern `fabric-gateway` Java client works; no legacy SDK. |
| **LevelDB** state DB (no CouchDB configured on the peers) | `docker inspect peer0.org1.example.com`: no `STATEDATABASE` env | **No rich (JSON) queries.** Lookup by CID must use a composite-key index. |
| Two orgs (`Org1MSP`, `Org2MSP`), one orderer, channel participation API (no system channel) | container env | Default endorsement policy = majority of 2 orgs = **both** must endorse each write. |
| Existing chaincode `basic` v1.0 (containers only) | `docker ps -a` | Source is **not on disk** anywhere (searched `E:`), so there is nothing to port. |
| Network is **stopped** (exited 4 months ago); `fabric-samples` copies on disk contain only `test-network/{compose,organizations}` (crypto material), **no `network.sh`** | filesystem | Bringing the network up is a real task (see section 8), not a click. Whether channel `crimechannel` still exists in the peers' ledgers is unverified. |
| Go 1.26 and Node 22 installed locally; `fabric-ccenv:2.5` image present | `go version` | Go chaincode can be unit-tested locally with `go test`. |

## 2. Language and framework

**Go with `fabric-contract-api-go` (v1.2.x, the version used by fabric-samples for 2.5; exact version
pinned at implementation).** Rejected: JavaScript/TypeScript (no existing code to port, weakest typing for
money-like integrity logic), Java (heavier build, chaincode builder image targets an older JDK), raw shim
(no benefit). Chaincode is a **new** chaincode named **`evidence`**, not a modification of `basic`.
`FabricProperties.chaincode` default changes from `basic` to `evidence`.

## 3. State model

World state, one JSON document per evidence item, plus an index. Nothing personal (C-06): ids, hashes,
CIDs, roles and timestamps only.

```
Key  EV~<evidenceId>          -> current record (JSON, below)
Key  CID~<cid>~<evidenceId>   -> 0x00      (composite key, append-only index)
```

```jsonc
{
  "docType": "evidence",
  "evidenceId": "EV-3f2b...",        // server-generated, chaincode only checks format + uniqueness
  "caseId": "CASE-2026-0042",        // string until case entities exist (E3, Phase 3)
  "evidenceType": "DIGITAL",         // DIGITAL | PHYSICAL
  "status": "COLLECTED",             // COLLECTED PROCESSING ANALYZED ARCHIVED RELEASED DISPOSED
  "version": 1,                      // +1 on EVERY successful write of this record
  "metadataCid": "bafk...", "metadataSha256": "<64 hex>",
  "fileCid": "bafk...", "fileSha256": "<64 hex>", "fileSize": 1048576,   // DIGITAL only, IMMUTABLE
  "createdBy": "<user uuid>", "createdByRole": "COLLECTOR", "createdAt": "<tx timestamp>",
  "updatedBy": "...", "updatedByRole": "...", "updatedAt": "<tx timestamp>",
  "lastAction": "CREATED", "lastReason": "",
  "currentCustodian": "<user uuid>", // = createdBy in Phase 2; two-step transfer is Phase 3 (D2/D5)
  "disposal": { "state": "NONE", "requestedBy": "", "requestedAt": "", "reason": "" }
}
```

- **The evidence file fields are immutable after creation.** `UpdateEvidence` can only replace the
  *metadata* pointer. A different file is a different evidence item. This is the strongest integrity
  property available and it is enforced in the chaincode, not only in Spring.
- **Versions come from history, not extra keys.** `GetHistoryForKey(EV~id)` returns every committed value
  with its `txId` and timestamp (C3), so "older versions stay readable" (B4) costs no extra storage. The peer
  history DB must be enabled (the Fabric default).
- **The CID index** answers "find evidence by CID" (B3) without CouchDB. Every CID ever attached to an item
  (its file CID and each metadata CID) is indexed and never removed. One CID may map to several items
  (the same file registered twice), so the lookup returns a list.
- **No function calls `DelState`** (C-02). There is no delete, bulk delete or purge function. A unit test
  greps the compiled function list for this.

## 4. Functions

`actor` = the two trailing arguments `actorId`, `actorRole` on every write (see section 5). Every write
except `CreateEvidence` also takes `expectedVersion`; a mismatch is rejected (logical optimistic locking,
independent of Fabric's own MVCC check). Every write increments `version`, stamps `updatedAt/By` from the
**transaction timestamp** (C4: the peer's, never a server or chaincode clock), writes the record, and emits
a chaincode event (section 6).

| Function | Kind | Arguments | Roles allowed | Rejected when | Effect |
|---|---|---|---|---|---|
| `CreateEvidence` | submit | `evidenceId, caseId, evidenceType, metadataCid, metadataSha256, fileCid, fileSha256, fileSize` + actor | COLLECTOR, FORENSIC_ANALYST | id exists; any id/hash/CID malformed; DIGITAL without file fields; PHYSICAL with file fields | v1, status COLLECTED, custodian = actor, indexes the CIDs |
| `UpdateEvidence` | submit | `evidenceId, expectedVersion, newMetadataCid, newMetadataSha256, reason` + actor | COLLECTOR, FORENSIC_ANALYST | not found; DISPOSED; version mismatch; blank reason; CID unchanged | replaces metadata pointer, v+1, indexes new CID; file fields untouched |
| `UpdateStatus` | submit | `evidenceId, expectedVersion, newStatus, reason` + actor | COLLECTOR, FORENSIC_ANALYST, PROSECUTOR | transition not allowed (below); target is DISPOSED; DISPOSED; blank reason | status set, v+1 |
| `RequestDisposal` | submit | `evidenceId, expectedVersion, reason` + actor | COLLECTOR, PROSECUTOR | DISPOSED; disposal already PENDING; blank reason | `disposal.state = PENDING` |
| `ApproveDisposal` | submit | `evidenceId, expectedVersion, note` + actor | **JUDGE** | disposal not PENDING; version mismatch; actor is the requester | status `DISPOSED`, disposal cleared |
| `RejectDisposal` | submit | `evidenceId, expectedVersion, note` + actor | **JUDGE** | disposal not PENDING; version mismatch | disposal cleared, status unchanged |
| `GetEvidence` | evaluate | `evidenceId` | any | not found | the record |
| `GetHistory` | evaluate | `evidenceId` | any | not found | list of `{txId, timestamp, record}`, oldest first (C3) |
| `FindByCid` | evaluate | `cid` | any | none (empty list) | list of evidence ids |

- **Approving disposal pins the version.** The approver passes the `expectedVersion` they reviewed, so
  a record edited after the request cannot be approved unseen.
- **DISPOSED freezes the record**: no update, no status change, no new disposal. It stays readable and
  verifiable forever (C-02, B5). Disposal is a ledger state change only; **no IPFS unpin happens**.
- **Status transitions (PROVISIONAL, finalised with D1 in Phase 3):**
  `COLLECTED→PROCESSING→ANALYZED→ARCHIVED→RELEASED`. Nothing moves backward; `DISPOSED` is reachable only
  through `ApproveDisposal`. Disposal may be requested from any non-DISPOSED status; the Judge's approval is
  the safeguard. Tighten this with D1 if you want disposal only from ARCHIVED/RELEASED.
- **Ledger error codes** (returned as `CODE: message`, so Spring can map them without string guessing):
  `EVIDENCE_NOT_FOUND`, `EVIDENCE_EXISTS`, `FORBIDDEN_ROLE`, `INVALID_ARGUMENT`, `INVALID_STATE`,
  `VERSION_CONFLICT`.

## 5. Identity and the "second role check" (A3), and its honest limit

A3 says roles are checked in Spring and again in the chaincode. In Phase 2 the Spring backend connects to
Fabric with **one** application identity (per-user Fabric identities are A2, Phase 3), so the chaincode
cannot read the end user's role from a certificate. Therefore:

1. Spring passes `actorId` and `actorRole` (from the JWT, C-05) as arguments.
2. The chaincode checks the role against the table above and rejects with `FORBIDDEN_ROLE`.
3. The chaincode also checks the invoking MSP is `Org1MSP` or `Org2MSP` (the backend's identity).

**What this protects against and what it does not:** it catches a bug or missing check in the Spring layer
(defence in depth) and records who acted in an immutable history. It does **not** stop a compromised
backend from claiming any role, because the backend is the only caller. That gap closes in Phase 3 (A2):
users are enrolled through Fabric CA with a `role` attribute in their certificate and the chaincode reads
`GetClientIdentity().GetAttributeValue("role")`, ignoring the argument. The chaincode is written with a
single `resolveActor(ctx, argId, argRole)` function so that switch is a one-function change.

## 6. Events (for G3, Phase 4)

Each write emits one chaincode event named `Evidence.<Action>` (`Created, MetadataUpdated, StatusChanged,
DisposalRequested, DisposalApproved, DisposalRejected`) with payload
`{evidenceId, version, txId, action}` and nothing else. Designing them now avoids changing every function
later; nothing in Phase 2 listens to them.

## 7. Determinism and concurrency

- Timestamps only from `GetTxTimestamp()`; ids only from arguments; JSON produced from typed structs
  (stable field order); no `time.Now`, no randomness, no map iteration in output.
- Two writes to the same item in the same block produce a Fabric `MVCC_READ_CONFLICT`. Spring surfaces
  that as `409 VERSION_CONFLICT` in Phase 2. Automatic retry is F5 (Phase 5).
- Reads use `evaluate` (peer-local, not ordered); writes use `submit` and wait for commit.

## 8. Spring side, deployment, and testing plan

**Spring (`FabricLedgerService`)**: `fabric-gateway` Java client over gRPC, connecting to the Org1 peer
(`localhost:7051`) with TLS. The application identity (cert + private key) and TLS CA cert are **file paths
supplied by environment variables** (`FABRIC_PEER_ENDPOINT`, `FABRIC_MSP_ID`, `FABRIC_TLS_CERT_PATH`,
`FABRIC_CERT_PATH`, `FABRIC_KEY_PATH`); keys never enter the repository (C-07). **This needs new
dependencies (C-04): `org.hyperledger.fabric:fabric-gateway` and a gRPC transport (`grpc-netty-shaded`).**
I am asking for approval here and will log them in DECISIONS.md.

**Deployment**: package with `peer lifecycle chaincode package`, install on both peers, approve for both
orgs, commit definition `evidence` version `1.0` sequence `1`. Blocker to resolve first: the copies of
`fabric-samples` on this machine lack `network.sh`, and the old network has been stopped for 4 months. Options:
(a) `docker start` the existing containers and check the channel, (b) fetch fabric-samples for 2.5 and
recreate the network with `createChannel -c crimechannel`. I would try (a) first and fall back to (b).

**Testing, in this order**
1. `go test` against a hand-written in-memory implementation of `ChaincodeStubInterface` (history,
   composite keys, tx timestamp): every function, every rejection, immutability of file fields, no `DelState`.
2. The same scenarios already exist as unit tests of the Java `InMemoryLedgerService` (built now), so the
   two implementations are checked against the same expectations.
3. Live: deploy, then drive `FabricLedgerService` through the real REST API (register, update, dispose,
   history, verify, corrupted-file check) and paste real output into `docs/TEST_CHECKLIST.md`.

## 9. Decisions I need from you

| # | Question | My default |
|---|---|---|
| G1 | New chaincode named `evidence` (Go), not reuse `basic`? | **Yes** |
| G2 | Immutable file fields; update changes metadata only? | **Yes** |
| G3 | Role table in section 4 (who may register / update / request / approve disposal)? Note ADMIN and AUDITOR can do **no** writes, JUDGE approves disposal | **As drafted** |
| G4 | Disposal requestable from any non-DISPOSED status (Judge is the safeguard)? | **Yes**, tighten with D1 |
| G5 | Roles passed as arguments now, certificate attributes in Phase 3, with the limit in section 5 accepted? | **Yes** |
| G6 | Approve dependencies `fabric-gateway` + `grpc-netty-shaded`? | **Yes** |
| G7 | Try to revive the stopped network first, else recreate it? | **Revive, then recreate** |


## 10. As built (2026-09-22)

Implemented in `chaincode/evidence/` (Go) and `FabricLedgerService` (Java) and verified end to end on the real network
(`docs/TEST_CHECKLIST.md` section P2-F, `docs/FABRIC_RUNBOOK.md`). Every function, role, rule and error code in sections 3-4 is
implemented as written. Differences and corrections:

1. **Section 1 facts were wrong on two points.** The real `fabric-samples` (with `network.sh`), the `peer` binary, a Go toolchain
   and the Node prototype are in WSL (`/home/debop/crime-evidence-mgmt`), not absent. The old `basic` chaincode's source is still not
   on disk. The network revived with `docker start` (G7 option a); nothing was recreated.
2. **Argument encoding:** `expectedVersion` and `fileSize` are strings parsed by the chaincode (D-031). Every write returns
   `TxResult{txId, timestamp, version}`.
3. **Deployed as v1.1 sequence 2**, not v1.0: a schema-validation defect in v1.0 that only a real peer exposes (D-032). A
   regression test guards it.
4. **`GetHistory` sorts by version** rather than relying on the peer's iteration order (tested with a simulated newest-first peer).
5. **Events** are emitted as designed (`Evidence.Created`, `MetadataUpdated`, `StatusChanged`, `DisposalRequested`,
   `DisposalRejected`, `DisposalApproved`, payload identifiers only); a test checks names and payload keys. Nothing listens yet (G3, Phase 4).
6. **Role source is the argument, exactly as approved (G5), and this is constraint C-08:** `authorise()` is the single place it is
   resolved. It authenticates only that the invoking MSP is Org1MSP or Org2MSP. It cannot authenticate the role. Do not call it authentication.
7. **Approver != requester** is implemented but cannot trigger with the approved role table (the sets are disjoint); a test forces the
   state to prove the rule.
8. **Not done, as designed:** no transfer functions (Phase 3), no per-user certificate attributes (A2), no state-based endorsement, no
   private data, no CouchDB indexes.

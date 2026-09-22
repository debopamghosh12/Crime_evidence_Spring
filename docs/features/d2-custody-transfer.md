# D2: Two-step custody transfer

**FEATURE_LIST:** D2. **Phase:** 3. **Status:** done, verified live through Spring -> real Fabric (chaincode v1.2 seq 3).

## Scoping
Custody must never move on the sender's say-so alone: the sender proposes, the named receiver accepts or rejects, and the sender can
withdraw. Every step is a ledger transaction, so the chain of custody is the ledger history (D3).

## What was built
| Endpoint | Who | Effect |
|---|---|---|
| `POST /api/evidence/{id}/transfers` `{expectedVersion, toUserId, reason, notes}` | the **current custodian** (role that can hold custody) | record gets `transfer.state = PENDING`; **custody unchanged** |
| `POST /api/evidence/{id}/transfers/accept` `{expectedVersion, note}` | the **named receiver** only | `currentCustodian` = receiver, state ACCEPTED |
| `POST /api/evidence/{id}/transfers/reject` | the named receiver | custody stays with the sender, state REJECTED |
| `POST /api/evidence/{id}/transfers/cancel` | the sender | state CANCELLED |
| `GET /api/transfers/pending` | any authenticated user | ids transferred **to me** and still pending |

- Roles that can hold custody: COLLECTOR, FORENSIC_ANALYST, PROSECUTOR, JUDGE. AUDITOR and ADMIN can neither send nor receive.
- One pending transfer per item at a time; none while a disposal is pending or after DISPOSED.
- The sender is always the token's user (C-05): a forged `from`/`sender`/`actorId` in the body is ignored (proved live, D2 section).
- The backend resolves `toUserId` in PostgreSQL: unknown/inactive -> 404 `RECEIVER_NOT_FOUND`; a role that cannot hold evidence -> 400
  `RECEIVER_CANNOT_HOLD_EVIDENCE`. The **ledger stores only the opaque user id and role** (C-06), never a name.
- Chaincode side: `custody.go` (`InitiateTransfer/AcceptTransfer/RejectTransfer/CancelTransfer/FindPendingTransfers`); pending index
  `TRF~<userId>~<evidenceId>` written at initiation, **never deleted** (no DelState anywhere, tested by AST) and filtered on read by the
  record's current state.

## Verification (TEST_CHECKLIST P3-L, D2 section)
Initiate -> pending list for the receiver (sender's list empty) -> AUDITOR initiating 403, second transfer while pending 409, a bystander
accepting 403, the **sender** accepting 403 -> receiver accepts (custodian changes, `TRANSFER_ACCEPTED`) -> old custodian trying to hand
it on 403 -> new custodian sends to the prosecutor, who rejects -> send to the judge, sender cancels -> receiver checks (AUDITOR 400,
unknown id 404, self 400) -> forged sender ignored. Automated: Go `transfer_test.go` (11), `InMemoryLedgerTransferTest` (9),
`LifecycleServicesTest`, `Phase3ControllersTest`; wire format pinned by real fixtures `p3-*.json`.

## Known limits
- **There is no endpoint to look users up**, so a client must already know the receiver's user id. Needs a small directory endpoint
  (owner decision: which fields, which roles may call it; listed in KNOWN_GAPS D).
- No expiry: a pending transfer stays pending until someone resolves it.
- Receiver role is supplied by the backend from PostgreSQL and trusted by the chaincode until A2 (C-08).

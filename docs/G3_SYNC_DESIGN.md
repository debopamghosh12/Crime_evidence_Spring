# G3: Ledger event listener — consistency design (for owner approval)

> **Status: DRAFT FOR APPROVAL. Nothing here is implemented.** Per the owner's instruction, this is written and
> reviewed before any code, the same way the A2 cutover ordering was. Once approved, G3 is built, then H1-H4 and A6
> straight through (their designs are sketched at the end, not gated on approval, but they depend on G3's schema).

## 1. The problem, precisely

The chaincode already emits one event per write (`docs/CHAINCODE_DESIGN.md` section 6): `Evidence.<Action>` with
payload `{evidenceId, version, txId, action}` — **identifiers only**, deliberately (no personal data on the wire,
C-06, and no function needed to change when events were designed). G3 must turn a stream of these into a Postgres
read model that H1 (search), H2 (dashboard), H3 (activity feed) and H4 (notifications) all read from, so that:

- **A missed event must not silently corrupt the read model** (an item stuck showing a stale status forever).
- **A redelivered event must not double-count** (H2's counts, H3's feed).
- **The read model must reflect ALL existing ledger data on first run**, not just future writes — the ledger
  already holds real evidence from every prior phase's live verification.

The two questions the owner asked to have answered explicitly, up front:

| Question | Answer, and why |
|---|---|
| Does the listener replay from the last block it saw, or silently drop events if it was down? | **Replays.** Fabric's chaincode event service is block-anchored, not a volatile pub/sub: the peer's ledger IS the retention, so any block range can be re-requested at any time, even from block 0. As long as the last successfully processed position is durably checkpointed, restarting the listener (or the whole app) after any outage — seconds or days — resumes exactly where it left off and nothing is lost. |
| What stops a redelivered event being double-applied? | **A unique database constraint, checked in the same transaction as the read-model write.** Not "try to design an event stream with exactly-once delivery" (Fabric doesn't promise that at the boundary of a checkpoint) — instead, make re-applying the same event a guaranteed no-op. |

## 2. What Fabric actually offers (checked against the real `fabric-gateway` 1.12.1 jar on the classpath, not assumed)

- `Network.newChaincodeEventsRequest(chaincodeName)` → a builder with `.startBlock(long)` **or** `.checkpoint(Checkpoint)`,
  `.build().getEvents()` → `CloseableIterator<ChaincodeEvent>` (a blocking iterator: `.next()` waits for the next
  event; `.close()` ends the stream). Scoped to ONE chaincode name (`evidence`) — `basic` and config-block events
  are never seen.
- `ChaincodeEvent`: `getBlockNumber()`, `getTransactionId()`, `getChaincodeName()`, `getEventName()`, `getPayload()`.
- `Checkpoint`: `getBlockNumber(): OptionalLong`, `getTransactionId(): Optional<String>`. Passing a `Checkpoint` to
  `.checkpoint(...)` resumes from that block **and correctly skips only the already-processed transaction within
  it** — the SDK's own resume semantics, not manual `startBlock+1` arithmetic (which would be wrong whenever more
  than one event lands in the same block, a real possibility since several writes can be ordered into one block).
- There is a built-in `FileCheckpointer` (writes to a local file) and `InMemoryCheckpointer` (no persistence).
  **Neither is used**: the checkpoint must be durable in the SAME database transaction as the read-model write it
  protects, and a local file next to the JVM is not that (it can desync from Postgres on a crash between the two
  writes, or simply not exist if the container is recreated). A small custom `Checkpoint` implementation backed by
  a Postgres row is used instead (section 4).

## 3. Where the seam is (constraint C-01)

Only `ledger/` may import Fabric types, so the raw event API is wrapped there, exactly like every other ledger
operation:

```java
// ledger/LedgerEventSource.java — new, narrow interface (not folded into LedgerService: nothing else needs it)
public interface LedgerEventSource {
    /** Blocking. Reconnection/backoff is the CALLER's job (section 6); this call returns/throws once per stream. */
    LedgerEventStream openEventStream(Checkpoint from);
}

// ledger/LedgerEvidenceEvent.java — the identifiers only, exactly what the chaincode payload carries
public record LedgerEvidenceEvent(String evidenceId, int version, String txId, long blockNumber, String action) {}

// ledger/Checkpoint.java — Java-only position marker; NOT the Fabric SDK's Checkpoint type, but shaped to build one
public record Checkpoint(Long blockNumber, String txId) {
    public static final Checkpoint NONE = new Checkpoint(null, null);
}
```

`FabricLedgerService` implements `LedgerEventSource` (it already owns the `Gateway`/`Network`/channel wiring from
A2), translating its `Checkpoint` into the SDK's `.checkpoint(...)` (present) or `.startBlock(0)` (absent — see
section 5) and mapping `ChaincodeEvent` → `LedgerEvidenceEvent`. `LedgerEventSource` is `!memory-ledger`-scoped,
same as `IdentityStore`/`WalletHealthIndicator` (A2): the in-memory reference ledger has no persistent block
history to listen to.

Everything downstream of `LedgerEventSource` — the consumer loop, the checkpoint store, the projection writer — is
a **new `sync/` package**, which never imports Fabric types, only `LedgerEventSource`/`LedgerEvidenceEvent` and
`LedgerService` (for the enrichment read, section 4).

## 4. Enrichment: the payload alone is not enough

`{evidenceId, version, txId, action}` has no actor, reason, case, or status — every event needs a follow-up READ
to be useful for H1-H4. On receiving an event, the consumer calls `LedgerService.getHistory(evidenceId)` (already
built, C3/D3) and finds the entry **whose `txId` matches the event's `txId`** (not "the current record" — a later
event may have already landed by the time this one is enriched, and matching by txId, not by "latest", makes the
enrichment correct regardless of arrival timing). That history entry has the full record at exactly that version:
status, case, actor, role, reason, ledger timestamp.

## 5. The atomic unit of work, per event

```
1. (outside any DB transaction) receive LedgerEvidenceEvent from the stream
2. (outside any DB transaction) ledger.getHistory(evidenceId) → find entry by txId
      not found / ledger read fails → throw; escalates straight to the STREAM-level failure path (section 9):
      this is a Fabric-side problem, not a Postgres one, so there is no local retry tier for it.
3. up to 3 times, with a SHORT fixed 200ms pause between attempts (section 9 tier 1 — rides out a sub-second
   Postgres blip WITHOUT tearing down the Fabric stream, which is expensive to reopen):
     BEGIN a Spring @Transactional boundary
       a. INSERT INTO evidence_activity (tx_id, ...) VALUES (...) ON CONFLICT (tx_id) DO NOTHING
          0 rows affected → this txId was already fully processed in an earlier run (a redelivery at a
             checkpoint boundary). COMMIT (no-op) and move to the next event from the stream. The checkpoint is
             NOT touched here: if the activity row already exists, the checkpoint was already advanced past it
             in the transaction that inserted it (checkpoint and activity insert are ALWAYS in the same
             transaction, see (c)), so there is nothing to do.
          1 row affected → a genuinely new event; continue.
       b. UPSERT evidence_projection (keyed by evidence_id): overwrite with this entry's fields IF
          this entry's version >= the stored version (or no row exists yet). A defensive guard, not expected to
          ever refuse a write given single-consumer, checkpoint-sequential processing — but cheap insurance
          against ever regressing the projection if that assumption is ever violated.
       c. UPDATE ledger_sync_checkpoint SET block_number = event.blockNumber, tx_id = event.txId
     COMMIT (a, b, c together: either the event is fully applied — activity row, projection, checkpoint, all
     three — or, on failure, none of them are)
     on success: done with this event, back to the stream's for-loop for the next one
   if all 3 local attempts fail: escalate to the STREAM-level failure path (section 9) — by this point a 200ms
   Postgres blip is a more plausible sustained Postgres problem, and reopening the stream costs no correctness
   (the event was never committed, so it replays exactly the same way after reconnecting).
```

`evidence_activity` therefore does two jobs at once: it is both **the idempotency log** (the `UNIQUE (tx_id)`
constraint is the actual dedup mechanism, not the checkpoint) and **the append-only feed H3 reads from** — one
table, not two, since every processed event already is a feed-worthy row.

## 6. The listener loop: reconnection, not silent loss

```
loop:
    checkpoint := read ledger_sync_checkpoint (Postgres)
    request := checkpoint.blockNumber != null
                 ? newChaincodeEventsRequest("evidence").checkpoint(toSdkCheckpoint(checkpoint))
                 : newChaincodeEventsRequest("evidence").startBlock(0)     // first ever run: replay all history
    try:
        stream := request.build().getEvents()
        onStreamOpened()                            # section 9: resets the failure counter, marks health UP
        for event in stream:                         # blocks until each new event; runs "forever"
            process(event)                           # section 5; an exhausted-retries escalation propagates out
    catch (any exception escalated from section 5, or the stream/iterator itself failing):
        log it; close the stream if open; onStreamFailed()   # section 9: counts the failure, backs off, may flip health to DOWN
    goto loop                                         # re-reads the checkpoint fresh each time and reconnects
```

Runs on its own background thread (`ApplicationRunner`/`ApplicationReadyEvent`, single-thread executor), started
after the application context is up, stopped gracefully (`@PreDestroy` / `SmartLifecycle`: close the iterator,
interrupt, join with a timeout) so a redeploy does not orphan a stream. **A crash, a peer restart, an orderer
outage, a container recreate — every failure mode ends up back at the top of this loop**, which re-reads
Postgres's checkpoint and reconnects from there. Nothing is ever silently dropped; the worst case is a bounded
delay (backoff) before catching up, never data loss. The loop **never gives up permanently** — replay-after-outage
holds no matter how long the outage lasts; section 9's backoff and health signal are pure observability on top of
that guarantee, not a circuit breaker that stops retrying.

## 7. Schema (Flyway `V4__event_sync.sql`, new migration, V1-V3 untouched)

```sql
CREATE TABLE ledger_sync_checkpoint (
    id           SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),   -- exactly one row, ever
    block_number BIGINT,
    tx_id        VARCHAR(64),
    updated_at   TIMESTAMPTZ NOT NULL
);
INSERT INTO ledger_sync_checkpoint (id, updated_at) VALUES (1, now());  -- starts empty: block_number/tx_id NULL

CREATE TABLE evidence_activity (          -- idempotency log AND the H3 feed source, see section 5
    id            UUID PRIMARY KEY,
    tx_id         VARCHAR(64)  NOT NULL UNIQUE,
    block_number  BIGINT       NOT NULL,
    evidence_id   VARCHAR(50)  NOT NULL,
    version       INT          NOT NULL,
    action        VARCHAR(30)  NOT NULL,
    case_id       VARCHAR(64),
    status        VARCHAR(10),
    actor_id      VARCHAR(36),
    actor_role    VARCHAR(20),
    reason        VARCHAR(2000),
    ledger_at     TIMESTAMPTZ  NOT NULL,   -- the ledger's own transaction timestamp (C4), not this server's clock
    processed_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_activity_evidence ON evidence_activity (evidence_id, version);
CREATE INDEX ix_activity_time     ON evidence_activity (ledger_at DESC);   -- H3 feed ordering

CREATE TABLE evidence_projection (        -- current state, for H1 search/filter and H2 aggregates
    evidence_id   VARCHAR(50)  PRIMARY KEY,
    case_id       VARCHAR(64),
    evidence_type VARCHAR(10)  NOT NULL,
    status        VARCHAR(10)  NOT NULL,
    version       INT          NOT NULL,
    current_custodian VARCHAR(36),
    created_by    VARCHAR(36)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    last_action   VARCHAR(30)  NOT NULL,
    last_reason   VARCHAR(2000)
);
CREATE INDEX ix_projection_case   ON evidence_projection (case_id);
CREATE INDEX ix_projection_status ON evidence_projection (status);
```
`evidence_projection` is a pure projection: nothing else writes to it, it can always be rebuilt by truncating it
(and `ledger_sync_checkpoint`) and replaying `evidence_activity`, or by resetting the checkpoint to NULL and
replaying the ledger from block 0. This is deliberate — the ledger is always the source of truth (C-01 spirit); a
corrupted read model is a Postgres problem, not a data-loss problem.

## 8. What this does not solve (stated up front, not discovered later)

- **Single active listener instance.** No coordination (e.g. a Postgres advisory lock) for running more than one;
  two instances would both try to consume and could race on the checkpoint update. Fine at this project's scale
  (one backend instance); noted as a scaling limit in `docs/KNOWN_GAPS.md` once built, not solved now.
- **One `GetHistory` read per event.** `GetHistory` returns the WHOLE version history of an item, which grows with
  its version count, so enrichment cost grows per item over its lifetime. Acceptable here (evidence items in this
  project have single-digit version counts); a chaincode function returning one version directly would be more
  efficient but does not exist and adding one touches the chaincode — out of scope for G3.
- **Ordering across DIFFERENT items is not guaranteed to match wall-clock order**, only per-item order is (Fabric
  orders per-key, and this design processes strictly in ledger order). H3's feed sorts by `ledger_at`, which is
  correct regardless.
- **This is sync, not the source of truth.** Every H1-H3 read is against Postgres for speed; anything that needs
  a tamper-evidence guarantee (verify, disposal decisions) still goes through `LedgerService` directly, unchanged.

## 9. Reconnect and backoff strategy (a transient DB blip is not the same failure mode as a genuine outage)

Two tiers, deliberately not one, because collapsing them into a single "any error → tear down and reconnect the
Fabric stream" policy would mean a single momentary Postgres hiccup pays the full cost of reopening a chaincode
event subscription (a new gRPC stream, a fresh checkpoint-aware resume) for no reason:

**Tier 1 — local DB retry (section 5 step 3), for `evidence_activity`/`evidence_projection`/`ledger_sync_checkpoint`
transaction failures only.** Up to 3 attempts, a short FIXED 200ms pause between them (not exponential — this tier
exists only to ride out a sub-second blip; if it is still failing after ~600ms of trying, it is not a blip and
tier 2 is more honest). The Fabric stream stays open and untouched throughout; only the DB write is retried.

**Tier 2 — stream-level exponential backoff, for anything that reaches it:** a Fabric read/stream error (section 5
step 2, or the event iterator itself failing), OR tier 1 exhausted. The stream (if open) is closed, and the next
reconnect attempt is delayed:

| Consecutive stream-level failures | Delay before the next reconnect attempt |
|---|---|
| 1 | ~1s |
| 2 | ~2s |
| 3 | ~4s |
| 4 | ~8s |
| 5 | ~16s |
| 6+ | ~30s (capped; retried forever at this interval, never gives up) |

Exponential, base 1s, factor 2, capped at 30s, with ±20% random jitter on each delay (avoids any synchronised
retry pattern; not that it matters much for a single instance, section 8, but it is free to add and standard
practice). This is distinct from, and does not affect, the earlier live-verified property that a peer restart
recovers in ~4-6s (docs/TEST_CHECKLIST.md P2-F.7 F3) — that recovery happens on the FIRST reconnect attempt (delay
~1s), well before backoff has grown large; the cap only matters for a genuinely sustained outage.

**Health signal, a NEW actuator component `eventSync`** (same pattern as `ledger`/`ipfs`/`wallet`): an in-memory
counter of consecutive tier-2 failures, reset to 0 the moment a stream successfully opens (`onStreamOpened()`,
before waiting for any event — opening cleanly is itself the signal, since events can be sparse). `eventSync`
reports:
- **UNKNOWN** before the listener's first connection attempt has completed (mirrors `LedgerHealthIndicator`'s
  "not configured yet" honesty for the brief window at startup).
- **UP** once at least one stream has opened successfully and fewer than 5 consecutive tier-2 failures have
  happened since.
- **DOWN** at 5 consecutive tier-2 failures (by then, backoff has already reached or is approaching its 30s cap,
  meaning roughly a minute of sustained failure, not a blip) — WITH THE FAILURE STILL BEING RETRIED underneath;
  DOWN is a signal for an operator to look, not a statement that sync has stopped trying.
- Overall `/actuator/health` aggregation: `eventSync` DOWN pulls overall status to DOWN (like `ledger`/`ipfs`), so
  a genuinely stuck listener is visible without polling `evidence_activity` for staleness by hand.

## 10. Testing plan

- Unit: a fake `LedgerEventSource` that can be told to emit a fixed event sequence, then INTERRUPT mid-stream, so
  the consumer's reconnect-and-resume behaviour is tested without a real network. Scenarios: duplicate delivery
  (redeliver an already-processed txId → no new activity row, no double count), a DB failure mid-transaction
  (nothing committed, checkpoint unchanged), first-run-with-no-checkpoint (starts at block 0), tier 1 succeeding
  on its 2nd attempt (no stream teardown), tier 1 exhausted escalating to tier 2, the failure counter and health
  state through a sequence of failures and a recovery, backoff delay values at each consecutive-failure count.
- Live: register/update/dispose a few items with the listener running, confirm `evidence_activity`/
  `evidence_projection` match the ledger; **stop the listener (or the whole app) between two writes, do the writes
  while it's down, restart it, confirm both appear with no duplicates and the checkpoint caught up** — this is the
  direct demonstration the owner asked for, the same way the A2 cutover had its own direct demonstration.

## 11. H1-H4 and A6, briefly (not gated on this approval, but read `evidence_activity`/`evidence_projection`)

- **H1 Search/filter:** `GET /api/evidence/search?caseId=&status=&officer=&from=&to=&q=&page=&sort=` over
  `evidence_projection` (Spring Data JPA Specifications), `q` as a simple `ILIKE` over `last_reason`/case number
  (no full-text index infrastructure added for this scale).
- **H2 Dashboard:** aggregate queries over `evidence_projection` (counts by status/type/case) and
  `evidence_activity` (activity over time, grouped by day).
- **H3 Activity feed:** `evidence_activity` ordered by `ledger_at DESC`, filtered to cases the requesting user is
  a member of (or all, for roles with unrestricted read — A5 case-level restriction is still not built).
- **H4 Notifications:** a `notifications` table, populated either (a) as a side effect of the SAME transaction in
  section 5 step (b) when `action` is one that matters (`TRANSFER_INITIATED` → notify the receiver,
  `STATUS_CHANGED`/`DISPOSAL_*` → notify case members), or (b) a tamper alert, which is NOT a ledger event at all
  — raised directly by `VerificationService` when a verify call finds TAMPERED, independent of G3. In-app only
  (poll `GET /api/notifications`); email explicitly out of scope unless trivial, per the owner's instruction.
- **A6 Access audit log:** a `audit_log` table (user, resource, action VIEW/DOWNLOAD/DENIED, time, IP), written by
  a thin AOP aspect around read endpoints plus the existing `GlobalExceptionHandler`/entry points for denied
  attempts. **Paired with the Phase 1 access-token-lag gap, per the owner's instruction:** the audit write path
  (not the authorization decision — that stays unchanged, the TTL gap is not being fixed here) additionally looks
  up `user.enabled` at write time and records a distinct `TOKEN_USED_AFTER_DEACTIVATION` entry when a request
  succeeds with a token belonging to a since-deactivated user, closing VISIBILITY into the gap without closing the
  gap itself — exactly as instructed.

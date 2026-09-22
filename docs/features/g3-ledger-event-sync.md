# G3: Ledger event listener

**FEATURE_LIST:** G3 (P1). **Phase:** 4. **Status:** done, verified live against real Fabric + PostgreSQL.
Full consistency design, approved by the owner before any code was written: `docs/G3_SYNC_DESIGN.md`.

## Scoping
"Listen to chaincode or block events and sync them into Postgres." The owner flagged sync consistency, not the
listening itself, as the real risk: a listener that misses events or double-processes them silently corrupts the
read model H1-H4 will be built on. The design doc (11 sections) was written and approved first, including an
added section on reconnect/backoff strategy the owner specifically asked for before implementation began.

## What was built
- **Idempotency:** `evidence_activity.tx_id` is UNIQUE; every event is applied via `INSERT ... ON CONFLICT (tx_id)
  DO NOTHING` in the SAME transaction as the projection upsert and the checkpoint advance. A redelivered event
  inserts zero rows and touches nothing else - one table serves as both the idempotency log and the H3 activity
  feed source.
- **Replay, not loss:** the checkpoint (`ledger_sync_checkpoint`, one row) is read fresh on every reconnect; an
  empty checkpoint means "start from block 0" (a first-ever run backfills the WHOLE ledger, not just future
  writes). Fabric's chaincode event service is block-anchored, so any outage - a crash, a restart, days offline -
  is recovered by reconnecting from the last committed position.
- **Two retry tiers** (`EventSyncListener`): tier 1 retries only a failed DATABASE transaction (up to 3 attempts,
  fixed 200ms), leaving the Fabric stream open; tier 2 (anything else, or tier 1 exhausted) tears the stream down
  and backs off exponentially (1s, 2s, 4s, 8s, 16s, capped at 30s, +/-20% jitter) before reconnecting. The loop
  never gives up permanently.
- **Health:** a new `/actuator/health` component `eventSync` - UNKNOWN before the first connection, UP once
  connected with fewer than 5 consecutive tier-2 failures, DOWN at 5 (an operator signal; the retry loop keeps
  running underneath it regardless).
- **Enrichment:** the chaincode event payload is identifiers only (`{evidenceId, version, txId, action}`, C-06);
  each event is matched against `LedgerService.getHistory(evidenceId)` by `txId` (not "current state", which
  would race if a later event lands first) to get the full record for that exact write.

## Verification (TEST_CHECKLIST P4-G3)
```
first-ever run (empty checkpoint): 164 activity rows / 90 projection rows backfilled from block 0 in one pass
register while the listener is running: appears in evidence_activity within ~2s
app fully stopped -> two items written directly to the chaincode (bypassing Spring entirely)
  -> confirmed ABSENT from Postgres while the app is down (165 activity rows, unchanged)
app restarted -> both items present, correct txIds, checkpoint caught up exactly (165 -> 167, block 224 -> 226)
a further restart with no new writes: activity/projection/checkpoint counts all unchanged (idempotency holds)
whole Phase 2 checklist re-run with the listener active: identical, 0 application errors
```
Automated: `EventProcessorTest` (the atomic unit of work: new event applies both writes, redelivery touches
neither), `EventSyncListenerTest` (8 tests: first-run-from-block-0, clean run, tier 1 succeeding on retry with NO
stream teardown, tier 1 exhausted escalating with exactly one counted failure, a stream failing partway, the
UNKNOWN-until-first-success health transition, the 5-failure threshold, backoff delay values at every consecutive
count), `EventSyncHealthIndicatorTest` (UNKNOWN/UP/DOWN mapping). 197 Java tests total.

## Known limits
- Single active listener instance; no coordination for running more than one (fine at this project's scale).
- One `GetHistory` read per event; cost grows with an item's version count over its lifetime.
- H1-H4/A6 are not built yet; they read `evidence_activity`/`evidence_projection`, built next.

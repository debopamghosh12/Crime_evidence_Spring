# F5: Upload consistency and retry

**FEATURE_LIST:** F5 (P1, "Compensation step, Spring Retry"). **Phase:** 5. **Status:** done, extends D-024's
pin-cleanup compensation (already built in Phase 2) with a retry on Fabric MVCC conflicts, scoped to
`register()`'s ledger write specifically. Verified by 6 focused unit tests plus a live regression check; the
genuine-conflict scenario itself could not be triggered live (see "Known limits").

## What already existed (D-024, Phase 2)
`EvidenceService.register()` orders its steps hash-then-IPFS-then-ledger; if the ledger write fails, any pins
already made are removed - but ONLY if the ledger confirms no evidence references that CID (the same file
registered twice shares one CID, so blind unpinning could destroy another record's content). This part is
unchanged by F5.

## What F5 adds
A bounded retry around `ledger.createEvidence(...)` specifically, triggered ONLY by a genuine **Fabric-level
MVCC read conflict** - two transactions racing to write the same key in the same block, where the loser never
committed at all. This is distinct from the chaincode's own `VERSION_CONFLICT` (a caller's `expectedVersion` is
genuinely stale), which is NOT retried: resubmitting a stale version would just fail the same way again.

- **New `LedgerErrorCode.CONCURRENT_WRITE_CONFLICT`** (owner-approved, same `HttpStatus.CONFLICT` as
  `VERSION_CONFLICT` - additive, no behaviour change for existing callers). `FabricErrors.translate`'s
  `mvccCommit` branch now returns this code instead of `VERSION_CONFLICT`.
- **`EvidenceService.createEvidenceWithRetry`**: up to 3 attempts, fixed 200ms delay between them - the exact
  same shape as `sync.EventSyncListener`'s already-approved tier-1 local retry (G3), reused rather than
  inventing a second retry policy. A plain Java loop, not the `spring-retry` library (owner-approved deviation
  from FEATURE_LIST's tech note - see D-063 for why: distinguishing the two `LedgerException` cases needs a
  custom `RetryPolicy` either way, so the library buys nothing here). On final failure, `compensate()` still
  runs exactly as before - the retry sits entirely inside the existing try/compensate block.

## Scope: register() only, not update/status/transfer/disposal
Found by reading the chaincode (`chaincode/evidence/evidence.go`), not assumed: `CreateEvidence` reads and
writes only keys derived from its OWN call arguments - `recordKey(evidenceID)` (a fresh, server-generated UUID)
and CID-index entries keyed by `(cid, evidenceID)` (distinct even when two items share a CID). It has no shared,
contended key with any other transaction, so blindly resubmitting the exact same arguments after a genuine MVCC
conflict is both safe and sufficient. The versioned writes (`update`, status change, custody transfer, disposal)
all read-modify-write the SAME `recordKey(evidenceID)` and could genuinely race - but retrying one of THOSE with
the same `expectedVersion` would not help, since by the time the retry's simulation runs the chaincode's own
version check would correctly see the version has moved on and reject it (a real `VERSION_CONFLICT`, not a
retriable one). A meaningful retry there needs a re-read-then-rebuild step specific to each call site's own
semantics - materially more than F5's "Compensation step, Spring Retry" tech note implies, and not built.

## Verification
`CreateEvidence` has no contended key, so a genuine Fabric-level MVCC conflict could not be naturally triggered
against real Fabric for this call - contriving one would mean adding artificial shared state to the chaincode
purely for a demo, which is scope creep. Verified instead:
- **6 unit tests** (`EvidenceServiceRetryTest`, a Mockito-controlled `LedgerService` so the exact failure
  sequence is under test control - stronger than a live trigger could offer anyway): succeeds on the 2nd
  attempt after one conflict; succeeds on the 3rd and final attempt; exhausts all 3 and still propagates the
  failure; never retries a chaincode `VERSION_CONFLICT` (fails on attempt 1); never retries an unrelated
  `ApiException`; compensation still runs after retries are exhausted.
- **Live regression** against real Fabric/PostgreSQL/IPFS: registered PHYSICAL evidence through the
  retry-wrapped path - succeeded on the first attempt, zero retry log lines, confirming the wrapper is fully
  transparent for the overwhelmingly common non-conflict case.
- Full suite: 232 Java tests, all passing (226 before F5 + 6 new).

## Known limits
- **Retry is scoped to `register()` only** (see "Scope" above) - `update`, status change, custody transfer and
  disposal are not retried on a Fabric MVCC conflict; each already surfaces the same `CONCURRENT_WRITE_CONFLICT`
  code (409) to the caller, who must retry manually today.
- **The genuine-conflict trigger itself is unverified live**, by design (no contended key exists to race on) -
  documented as an accepted gap (KNOWN_GAPS) rather than claimed as tested when it could not be.
- No configurable retry count/delay (fixed at 3 attempts / 200ms, matching G3's constants) - not exposed via
  configuration, since nothing asked for that.

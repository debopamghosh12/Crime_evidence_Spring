# Bug (latent): a ledger record without a "transfer" key parsed with `transfer == null`

**Found:** 2026-09-22, while adding the Phase 3 wire-format tests. **Fixed:** same day. **Feature affected:** D2/D3 (custody), and any
read of evidence written before transfers existed. **Never seen in production paths**, see "Why it was hidden".

## Symptom

A new test parsed `src/test/resources/fabric/get-physical.json` (real output captured from chaincode **v1.1**, before transfers existed;
it contains no `"transfer"` key) and failed:
```
FabricLedgerServiceTest.aRecordWrittenBeforeTransfersExistedStillParsesAsNoTransfer:159
   assertThat(r.transfer()).isNotNull()   ->   r.transfer() was null
```

## Diagnosis

1. Jackson leaves a record component `null` when the key is absent. `LedgerEvidenceRecord.transfer` is a component, so a v1.1 record read
   in Java had `transfer() == null`, and `CustodyService`/`EvidenceResponse` code that calls `record.transfer().state()` would throw
   `NullPointerException` (a 500).
2. It was hidden because chaincode v1.2 already normalises: `EvidenceContract.load()` and `GetHistory` call `normalise()`, which fills
   `transfer = {state: NONE}` for old records before returning them. The live check "an item written BEFORE transfers existed still
   reads: GET 200, timeline 200" therefore passed. The Java side was relying on the chaincode alone.

## Fix

Defence in depth: the `LedgerEvidenceRecord` canonical constructor turns an absent `transfer` into `Transfer.none()`. Either side alone
is now sufficient. Test: `FabricLedgerServiceTest.aRecordWrittenBeforeTransfersExistedStillParsesAsNoTransfer` (uses the real v1.1
fixture). The Phase 3 fixtures (`p3-*.json`) are real v1.2 output and are tested alongside it.

## Verification

Full Java suite after the fix: `Tests run: 171, Failures: 0, Errors: 0, Skipped: 0` (TEST_CHECKLIST P3.1).

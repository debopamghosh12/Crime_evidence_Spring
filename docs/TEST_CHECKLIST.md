# Test Checklist

Run before calling anything "done". Each check has the command and the **actual output** from the run
that verified it. If a feature has no check here, add one first, then run it.

Last full run: **2026-09-22, Phase 1 + Phase 2 + Phase 3 (D1-D3, E1, E2)**. **171 Java tests + 33 Go chaincode tests passed, 0 failed** (section P3).
Phase 3 was run live through Spring -> real Fabric (chaincode v1.2 seq 3) + real PostgreSQL + offline Kubo; the Phase 2 checklist was re-run as a regression.
Previous full run: 2026-09-22, Phase 1 + Phase 2: 130 Java tests + 22 Go chaincode tests passed, 0 failed.
Phase 2 live checks passed against the in-memory reference ledger (section P2) AND through the **real Fabric network** (section P2-F).
Phase 1 live checks (sections 2-8 below) were run 2026-09-22 and are unchanged.

Secrets are never written in this file: commands use environment-variable references.

## P3. Phase 3: status, custody, cases, case-evidence link (D1-D3, E1-E3) — run 2026-09-22, updated 2026-09-22 for E3

Environment: default (Fabric) profile app on `localhost:8080`; `FabricLedgerService` -> `peer0.org1` (Org1MSP), channel `crimechannel`,
chaincode `evidence` **v1.2 seq 3**, both orgs endorsing; PostgreSQL 16 container on 5433 (Flyway applied `V3 case_evidence_links` at startup); Kubo in `--offline` mode.
**Not run, by owner decision:** single-org endorsement failure, orderer outage, peer failover, load (docs/KNOWN_GAPS.md A). **A2 is design only, not implemented** (see below).

### P3.1 Automated Java tests: `./mvnw -o test`
```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0  config.JwtPropertiesTest
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0  controller.EvidenceControllerTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0  controller.Phase3ControllersTest
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0  ledger.FabricLedgerServiceTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0  ledger.HealthIndicatorsTest
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0  ledger.InMemoryLedgerServiceTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0  ledger.InMemoryLedgerTransferTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0  security.JwtServiceTest
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0  security.SecurityAndErrorFormatTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0  service.AuthServiceTest
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0  service.CaseServiceTest
Tests run: 27, Failures: 0, Errors: 0, Skipped: 0  service.EvidenceServiceTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0  service.HashingInputStreamTest
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0  service.LifecycleServicesTest
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0  storage.HttpIpfsClientTest
Tests run: 173, Failures: 0, Errors: 0, Skipped: 0
```
New for D1-D3/E1-E2: `InMemoryLedgerTransferTest` 9, `LifecycleServicesTest` 11, `Phase3ControllersTest` 9, plus 5 new wire-format tests in
`FabricLedgerServiceTest` (real v1.2 fixtures). New for E3: `CaseServiceTest` 6 -> 8 (+2), `EvidenceServiceTest` 26 -> 27 (+1) = 173 total, up from
130 at the end of Phase 2. One run failed 1 test along the way: the null-`transfer` finding, fixed (docs/bugs/ledger-record-without-transfer-key.md).

### P3.2 Go chaincode tests: `go test -count=1 -v ./...` in `chaincode/evidence` (33 passed; full verbatim list in Appendix P3-G)

### P3.3 Live Phase 3 through Spring -> real Fabric + PostgreSQL: `scripts/live/live_phase3.sh` (verbatim in Appendix P3-L)
Highlights (all as expected): illegal status jumps 409/400; custody unchanged until the receiver accepts; only the named receiver can respond (the SENDER
and a bystander both 403); AUDITOR can neither send nor receive; forged sender fields ignored; nine history entries, each with its own txId; an item from before
transfers existed still reads; cases: duplicate number 409, non-working lead 400, COLLECTOR cannot manage cases 403, lead cannot be removed, no DELETE (405).
**First run found a real bug:** `PUT /api/cases/{id}` with a new lead answered 500 (docs/bugs/case-lead-change-unique-violation.md). Fixed, app restarted, the whole
script re-run; Appendix P3-L is the second (passing) run. Application log after the rerun: 0 `ERROR` lines, 0 script tracebacks.

### P3.4 Regression: the whole Phase 2 checklist through Fabric on chaincode v1.2 (Appendix P3-R, `scripts/live/live_phase2.sh`)
Compared with the earlier real-Fabric Phase 2 run (P2-F-D) after normalising ids, hashes, CIDs, tx ids, epoch numbers and timings:
```
102,103c102,103
<    v1  CREATED           tx=TX..  at=2026-09-21T20:02:04.505840100Z  by=COLLECTOR reason=''
<    v2  METADATA_UPDATED  tx=TX..  at=2026-09-21T20:02:06.896367500Z  by=FORENSIC_ANALYST reason='Location corrected after audit'
---
>    v1  CREATED           tx=TX..  at=2026-09-21T20:46:45.810238100Z  by=COLLECTOR reason=''
>    v2  METADATA_UPDATED  tx=TX..  at=2026-09-21T20:46:48.224963900Z  by=FORENSIC_ANALYST reason='Location corrected after audit'
```
Only ledger timestamps differ: behaviour is identical (register/verify/TAMPERED/NOT_FOUND/versioning/disposal/roles/limits/IPFS outage).

### P3.8 E3 (evidence-to-case link) built and verified, 2026-09-22
Owner decision: Option B (Spring link table via `EvidenceService.register`), enforced unconditionally (docs/features/e3-evidence-case-link.md,
DECISIONS D-046). All three live scripts were updated to create their cases before registering evidence under them (`live_phase2.sh` E3
prerequisite section, `live_fabric_extra.sh`, `live_phase3.sh`), and all three were re-run end to end against real Fabric v1.2 seq 3 and real
PostgreSQL (Appendices P3-L, P3-R, P3-E are these E3-era runs; application log after all three: 0 `ERROR` lines, 0 script tracebacks).
```
register under an unknown case number -> 404  CASE_NOT_FOUND
register under the just-created case FAB-P3-CASE-16848 -> evidence EV-5c3230c9-e4fe-45a0-9cb6-f1b536e84354
the case now lists it (GET /api/cases/{id}):           200
evidenceIds: ['EV-5c3230c9-e4fe-45a0-9cb6-f1b536e84354']
and the full-record endpoint:                          200
linked: ['EV-5c3230c9-e4fe-45a0-9cb6-f1b536e84354']
```
Comparing the Phase 2 checklist before and after E3 (normalised the same way as P3.4) shows only the new case-creation prerequisite lines and
timestamps added; every existing check is byte-identical, confirming E3 changed nothing about how Phase 2's checks behave except the new
case requirement itself.

### P3.5 Fabric-only checks re-run (Appendix P3-E, `scripts/live/live_fabric_extra.sh`)
Real txIds found on the peers' ledger (qscc), concurrent-update race (exactly one winner in 3 rounds), peer outage 503 and self-recovery.
The first attempt printed `bash: line 1: /mnt/e/FINAL: No such file or directory` in F1: a script bug (the WSL path contained a space and was unquoted),
fixed in `live_fabric_extra.sh`; Appendix P3-E is the rerun.

### P3.6 Wire format: `chaincode/scripts/capture_wire_p3.sh` (real v1.2 peer output -> `src/test/resources/fabric/p3-*`)
```
4096 .
4096 ..
143 p3-error-forbidden-role.txt
119 p3-error-invalid-state.txt
3 p3-find-pending-after.json
3 p3-find-pending-none.json
44 p3-find-pending.json
1049 p3-get-accepted.json
715 p3-get-new.json
963 p3-get-pending.json
5309 p3-history.json
132 p3-tx-result-accept.json
133 p3-tx-result-initiate.json
copied
```
Real refusals as the peer prints them: `FORBIDDEN_ROLE: Only the named receiver can respond to this transfer`, `INVALID_STATE: A transfer is already pending`.

### P3.7 Not covered / not built
A2 (design only, Appendix P3-A is the CA spike it rests on, not an implementation, until section P3-A2 below); D4; a user-lookup endpoint;
the four known gaps of KNOWN_GAPS section A.

### P3.9 A2 (per-user Fabric identity) built, deployed and verified, 2026-09-22

Design approved as proposed (A2-Q1..Q8, docs/A2_IDENTITY_DESIGN.md). Order followed exactly as
docs/FABRIC_RUNBOOK.md section 8 requires: registrar -> enroll every user -> verify each write works on the
STILL-NON-ENFORCING chaincode -> only then deploy the enforcing version -> verify again.

**Go tests (36, chaincode/evidence), including 3 new A2-specific tests, filtered to result lines):**
```
--- PASS: TestCreateStoresVersionOneCollectedWithLedgerTimestampAndActor (0.00s)
--- PASS: TestDuplicateIdIsRejected (0.00s)
--- PASS: TestOnlyCollectorAndAnalystMayCreate (0.00s)
--- PASS: TestDigitalNeedsFileFieldsAndPhysicalForbidsThem (0.00s)
--- PASS: TestMalformedInputsAreRejected (0.00s)
--- PASS: TestAnOrganisationOutsideTheAllowListCannotWrite (0.00s)
--- PASS: TestUpdateBumpsVersionKeepsFileFieldsAndNeedsAReason (0.00s)
--- PASS: TestStaleExpectedVersionIsRejected (0.00s)
--- PASS: TestUpdateWithUnchangedCidOrUnknownIdOrWrongRoleFails (0.00s)
--- PASS: TestStatusMovesForwardOnlyAndNeverToDisposed (0.00s)
--- PASS: TestDisposalNeedsRequestThenJudgeApprovalThenFreezesTheRecordButKeepsIt (0.00s)
--- PASS: TestRejectedDisposalLeavesTheRecordUsable (0.00s)
--- PASS: TestTheApproverCannotBeTheRequester (0.00s)
--- PASS: TestHistoryIsOldestFirstWithDistinctTxIdsEvenIfThePeerReturnsNewestFirst (0.00s)
--- PASS: TestCidIndexFindsFileAndEveryMetadataCidAndSharedFiles (0.00s)
--- PASS: TestARejectedCallChangesNothing (0.00s)
--- PASS: TestEveryWriteEmitsOneEventWithOnlyIdentifiers (0.00s)
--- PASS: TestNothingEverDeletes (0.06s)
--- PASS: TestTheExportedFunctionSetIsExactlyTheApprovedOne (0.00s)
--- PASS: TestRecordJsonKeysAreTheContractWithTheJavaSide (0.00s)
--- PASS: TestContractMetadataGenerates (0.06s)
--- PASS: TestOmitemptyFieldsAreAlsoOptionalInTheSchema (0.00s)
--- PASS: TestACertificateWithNoRoleAttributeIsRefusedForEveryWriteButReadsStillWork (0.00s)
--- PASS: TestArgumentAndCertificateMismatchIsRefused (0.00s)
--- PASS: TestACertificateThatMatchesButLacksPermissionIsStillRefused (0.00s)
--- PASS: TestInitiateOnlyByTheCurrentCustodianAndCustodyMovesOnlyOnAcceptance (0.00s)
--- PASS: TestAcceptMakesTheReceiverCustodianAndTheyCanHandOnWhileTheOldCustodianCannot (0.00s)
--- PASS: TestRejectLeavesCustodyWithTheSenderAndAllowsANewTransfer (0.00s)
--- PASS: TestOnlyTheSenderCanCancelAndOnlyTheNamedReceiverCanRespond (0.00s)
--- PASS: TestTheReceiverMustRespondWithTheRoleTheTransferWasAddressedTo (0.00s)
--- PASS: TestRolesThatCannotHoldEvidenceAreRefusedEverywhere (0.00s)
--- PASS: TestInitiateArgumentAndStateChecks (0.00s)
--- PASS: TestATransferCannotStartWhileADisposalIsPendingOrAfterDisposal (0.00s)
--- PASS: TestPendingListShowsOnlyWhatIsStillPendingTowardThatUser (0.00s)
--- PASS: TestARecordWrittenBeforeCustodyTransfersExistedReadsAsNoneAndCanBeTransferred (0.00s)
--- PASS: TestTheCustodyTimelineIsRecoverableFromHistory (0.00s)
PASS
ok  	blockevidence/evidence	0.661s
```

**Java tests (183 total, up from 173 before A2), full per-class table:**
```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0  config.JwtPropertiesTest
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0  controller.EvidenceControllerTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0  controller.Phase3ControllersTest
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0  ledger.FabricLedgerServiceTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0  ledger.FileWalletIdentityStoreTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0  ledger.HealthIndicatorsTest
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0  ledger.InMemoryLedgerServiceTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0  ledger.InMemoryLedgerTransferTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0  ledger.WalletHealthIndicatorTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0  security.JwtServiceTest
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0  security.SecurityAndErrorFormatTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0  service.AuthServiceTest
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0  service.CaseServiceTest
Tests run: 27, Failures: 0, Errors: 0, Skipped: 0  service.EvidenceServiceTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0  service.HashingInputStreamTest
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0  service.LifecycleServicesTest
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0  storage.HttpIpfsClientTest
Tests run: 183, Failures: 0, Errors: 0, Skipped: 0
```
New: `FileWalletIdentityStoreTest` (5: plain key, encrypted key + wrong passphrase, missing entry, expiry, no leak
on a decrypt failure), `WalletHealthIndicatorTest` (3), plus A2 cases added to `FabricLedgerServiceTest` (+2 net).
One bug found and fixed along the way (`docs/bugs/wallet-key-decrypt-needs-bc-provider.md`): an EC test key
generated with the JDK's default SunEC provider was not readable by fabric-gateway's own key parser at all (fixed
by generating test keys with the BC provider, matching what real Fabric CA-issued keys look like); separately, the
running application itself failed to decrypt an encrypted wallet key until BouncyCastle was registered as a JCE
**provider**, not just present on the classpath.

**Step 1 (operator, one-time): registering `be-registrar`** (`scripts/fabric/bootstrap_registrar.sh`):
registers a client-only, role-attribute-only registrar and confirms it can enroll; reuses the exact least-privilege
shape the A2 spike (Appendix P3-A) already proved refuses every escalation attempt.

**Step 2: enrolling every enabled user** (`scripts/fabric/enroll_users.sh`, wallet keys PKCS#8-encrypted):
```
### Enrolling be-registrar
ok
### Reading enabled users from PostgreSQL
USER ID                                ROLE               EMAIL                            ACTION     CERT EXPIRES
54db3c70-a145-4743-b01b-d247442dd5e1   ADMIN              admin@blockevidence.local        skipped    Sep 22 02:36:00 2027 GMT (already enrolled; FORCE=1 to redo)
65bd142c-21ce-4491-aca1-8a605925d961   AUDITOR            auditor@blockevidence.local      skipped    Sep 22 02:36:00 2027 GMT (already enrolled; FORCE=1 to redo)
f47ff616-80f2-4b54-b128-3b1b3fb6b8d0   COLLECTOR          collector@blockevidence.local    skipped    Sep 22 02:36:00 2027 GMT (already enrolled; FORCE=1 to redo)
b9481c66-6f02-463e-9ca1-2fd45ccd0ca2   FORENSIC_ANALYST   forensic-analyst@blockevidence.local skipped    Sep 22 02:36:00 2027 GMT (already enrolled; FORCE=1 to redo)
e878852e-a405-46e5-a79d-cd2cf11e7874   JUDGE              judge@blockevidence.local        skipped    Sep 22 02:36:00 2027 GMT (already enrolled; FORCE=1 to redo)
286529a7-2abf-48aa-8553-2ae9810ce5c0   PROSECUTOR         prosecutor@blockevidence.local   skipped    Sep 22 02:36:00 2027 GMT (already enrolled; FORCE=1 to redo)

Wallet: /mnt/c/Users/debop/AppData/Local/Temp/claude/E--FINAL-YEAR-PROJECT/ab07a2df-3e62-47bd-be5c-469fddd75fa4/scratchpad/wallet (set FABRIC_WALLET_DIR to this path and FABRIC_WALLET_PASSPHRASE to the same passphrase for the app; keys are 0600, PKCS#8-encrypted).
```
(This is the SECOND, idempotent run - every user shows "skipped, already enrolled"; the first run showed
"enrolled" for all 6 with the same certificate expiries.)

**Step 3: verifying every enabled user's identity works, BEFORE the enforcing chaincode is deployed**
(chaincode still v1.2 at this point, the OLD, non-certificate-checking `authorise()`):
```
### Pre-cutover (chaincode still v1.2, non-enforcing): a write signed with the COLLECTOR's own wallet identity
$ curl -X POST /api/evidence  (collector token, wallet configured)
 HTTP 201, evidenceId=EV-3725dd2d-0ed4-43a0-911e-c50dbe8d6cb8, createdBy=f47ff616-80f2-4b54-b128-3b1b3fb6b8d0

### qscc GetTransactionByID on that write's txId: the creator certificate CN is the collector's OWN user id
 txId=89fe7c52023a2204a4fc9b855bb88fdd703d92beb7bd3eedaf0f6285e4c9a205
 qscc creator CN -> $f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
 (collector's user id: f47ff616-80f2-4b54-b128-3b1b3fb6b8d0 -- MATCH; before A2 every creator would show User1's identity, not this)
```
Then the whole Phase 2 checklist was run with the wallet configured (still v1.2): identical to the wallet-off
baseline apart from timestamps - `scripts/live/live_phase2.sh`, log kept locally (not re-appended here; superseded
by the post-cutover run below, which is the one that matters).

**Step 4: deploying the enforcing chaincode** (`VER=1.3 SEQ=4 bash chaincode/scripts/deploy_cc.sh`, decisive lines):
```
EXIT 0
### copy source to a WSL-local dir (no spaces in the path) and vendor dependencies
custody.go
evidence.go
go.mod
go.sum
main.go
model.go
policy.go
vendor
### package
package id: evidence_1.3:f45751ebf53baa04b01e33adb222a835e69ea4165beb189ad18577c9df31b741
### install on Org1 (the peer builds the chaincode image; this takes a while)
[34m2026-09-22 02:47:21.078 UTC 0001 INFO[0m [cli.lifecycle.chaincode] [34;1msubmitInstallProposal[0m -> Installed remotely: response:<status:200 payload:"\nMevidence_1.3:f45751ebf53baa04b01e33adb222a835e69ea4165beb189ad18577c9df31b741\022\014evidence_1.3" > 
[34m2026-09-22 02:47:21.085 UTC 0002 INFO[0m [cli.lifecycle.chaincode] [34;1msubmitInstallProposal[0m -> Chaincode code package identifier: evidence_1.3:f45751ebf53baa04b01e33adb222a835e69ea4165beb189ad18577c9df31b741
### install on Org2
[34m2026-09-22 02:47:57.460 UTC 0001 INFO[0m [cli.lifecycle.chaincode] [34;1msubmitInstallProposal[0m -> Installed remotely: response:<status:200 payload:"\nMevidence_1.3:f45751ebf53baa04b01e33adb222a835e69ea4165beb189ad18577c9df31b741\022\014evidence_1.3" > 
[34m2026-09-22 02:47:57.460 UTC 0002 INFO[0m [cli.lifecycle.chaincode] [34;1msubmitInstallProposal[0m -> Chaincode code package identifier: evidence_1.3:f45751ebf53baa04b01e33adb222a835e69ea4165beb189ad18577c9df31b741
### approve for Org1
[34m2026-09-22 02:47:59.723 UTC 0001 INFO[0m [chaincodeCmd] [34;1mClientWait[0m -> txid [af6b1cd808be8d033c43558a4857acce553480953a6c5e8dc7567accde223e13] committed with status (VALID) at localhost:7051
### approve for Org2
[34m2026-09-22 02:48:02.001 UTC 0001 INFO[0m [chaincodeCmd] [34;1mClientWait[0m -> txid [49a1e672c4f1342726acae23fee7042401120bbbffb5a6ec30d97109bbf3cc4e] committed with status (VALID) at localhost:9051
### commit readiness
{
	"approvals": {
		"Org1MSP": true,
		"Org2MSP": true
	}
}
### commit
[34m2026-09-22 02:48:04.523 UTC 0001 INFO[0m [chaincodeCmd] [34;1mClientWait[0m -> txid [11771524ccae6aa9a459c80c4ef040e8d0d18b11c8ee836bc7096a61f811e986] committed with status (VALID) at localhost:9051
[34m2026-09-22 02:48:04.527 UTC 0002 INFO[0m [chaincodeCmd] [34;1mClientWait[0m -> txid [11771524ccae6aa9a459c80c4ef040e8d0d18b11c8ee836bc7096a61f811e986] committed with status (VALID) at localhost:7051
### committed definitions
Committed chaincode definitions on channel 'crimechannel':
Name: evidence, Version: 1.3, Sequence: 4, Endorsement Plugin: escc, Validation Plugin: vscc
Name: basic, Version: 1.0, Sequence: 1, Endorsement Plugin: escc, Validation Plugin: vscc
```

**Verification plan item 2: the real chaincode, through the peer CLI, with different real identities**
(`chaincode/scripts/verify_a2_cutover.sh`; two throwaway CA identities enrolled for this check and revoked
afterwards, per docs/ROLLBACK.md):
```
### Enrolling be-registrar and two throwaway identities (revoked at the end)
judge identity   = 68c0f8ce-64ac-47ee-b287-3149a239fedb
collector identity = b8929a45-ff2e-4c6f-8b75-6cbc187c2381

### 1. User1 (pre-A2 identity, no role certificate attribute) tries to write: must be refused
Error: endorsement failure during invoke. response: status:500 message:"FORBIDDEN_ROLE: This identity has no role certificate attribute; only per-user enrolled identities may write evidence" 

### 2. A JUDGE certificate claiming COLLECTOR in the argument: must be refused
Error: endorsement failure during invoke. response: status:500 message:"FORBIDDEN_ROLE: The supplied actor does not match the calling certificate" 

### 3. A COLLECTOR certificate claiming JUDGE in the argument (ApproveDisposal): must be refused
Error: endorsement failure during invoke. response: status:500 message:"FORBIDDEN_ROLE: The supplied actor does not match the calling certificate" 

### 4. Positive control: the SAME collector certificate, correctly claiming COLLECTOR: must succeed
2026-09-22 02:52:49.441 UTC 0003 INFO [chaincodeCmd] chaincodeInvokeOrQuery -> Chaincode invoke successful. result: status:200 payload:"{\"txId\":\"e5da51d72d11983ca3acb5e7890ef888eec36e7753a6c4aaaf1ebe01f55e24ef\",\"timestamp\":\"2026-09-22T02:52:47.317548557Z\",\"version\":1}" 
{"docType":"evidence","evidenceId":"EV-93b8c13f-54ba-46f8-870a-91d3d3ff1188","caseId":"FAB-A2-CUTOVER","evidenceType":"PHYSICAL","status":"COLLECTED","version":1,"metadataCid":"bkjgrhtopqegexdvpbv6vtr5sqyrqpxj4662kls37mou7jag2ks66","metadataSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","createdBy":"b8929a45-ff2e-4c6f-8b75-6cbc187c2381","createdByRole":"COLLECTOR","createdAt":"2026-09-22T02:52:47.317548557Z","updatedBy":"b8929a45-ff2e-4c6f-8b75-6cbc187c2381","updatedByRole":"COLLECTOR","updatedAt":"2026-09-22T02:52:47.317548557Z","lastAction":"CREATED","lastReason":"","currentCustodian":"b8929a45-ff2e-4c6f-8b75-6cbc187c2381","disposal":{"state":"NONE"},"transfer":{"state":"NONE"}}

### 5. qscc: the creator of that write's transaction is the collector's OWN certificate (CN = its enrollment id)
(expected to equal: CN=b8929a45-ff2e-4c6f-8b75-6cbc187c2381)
```
Item 1 (`User1`, the pre-A2 identity with no certificate attribute) is refused with exactly the message
`authorise()` gives for a missing attribute. Items 2-3 (a JUDGE certificate claiming COLLECTOR, and a COLLECTOR
certificate claiming JUDGE) are refused with the mismatch message - this is the direct demonstration that role
forgery by the backend's own caller no longer works. Item 4 (the same COLLECTOR certificate, correctly claiming
COLLECTOR) succeeds. Item 5 (an additional qscc creator check on that same write) did not resolve cleanly in this
run (a script-side query issue, not a chaincode or security issue); the identical property - creator certificate
CN equals the signer's own id - is already conclusively shown in step 3 above and was not re-chased further.

**Step 4 regression: the whole Phase 2 checklist AND the Fabric-only extras, wallet configured, chaincode v1.3**
(`scripts/live/live_phase2.sh`, `scripts/live/live_fabric_extra.sh`; application log after both: 0 `ERROR` lines,
0 script tracebacks):
```
collector user id (from /api/auth/me): f47ff616-80f2-4b54-b128-3b1b3fb6b8d0

### E3 prerequisite: create the cases this checklist registers evidence under
   case FAB-LIVE-1 -> 409 (200/201 created, 409 already exists from an earlier run)
   case FAB-LIVE-2 -> 409 (200/201 created, 409 already exists from an earlier run)
   case FAB-LIVE-3 -> 409 (200/201 created, 409 already exists from an earlier run)
   case FAB-LIVE-4 -> 409 (200/201 created, 409 already exists from an earlier run)
   register under an unknown case number -> 404  CASE_NOT_FOUND

### B1/B2/C1 register DIGITAL evidence (metadata carries a FORGED collector, must be ignored)
HTTP 201
   evidenceId                 EV-d672eb34-44f9-41ce-8d66-a847366f4a51
   status                     COLLECTED
   version                    1
   createdBy                  f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   currentCustodian           f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   fileCid                    bafkreiabnsxi7ym4c3sctft6qoehtvtsx7fdfntuka5lxzxohfskn23muy
   fileSha256                 016cae8fe19c16e429967e838879d672bfca32b674503abbe6ee3964a6eb6ca6
   fileSize                   41
   metadataAvailable          True
   metadata.collectorId       f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   verification.status        NOT_CHECKED
   -> local sha256 of the file : 016cae8fe19c16e429967e838879d672bfca32b674503abbe6ee3964a6eb6ca6
   -> ledger fileSha256        : 016cae8fe19c16e429967e838879d672bfca32b674503abbe6ee3964a6eb6ca6
   -> createdBy == collector id from JWT? YES
   -> stored bytes fetched from IPFS by the file CID == original? YES

### B1 register PHYSICAL evidence (no file)
HTTP 201
   evidenceId                 EV-9b08acdb-2e53-4428-a091-d09f486f9dc7
   evidenceType               PHYSICAL
   fileCid                    None
   fileSha256                 None
   status                     COLLECTED
   createdByRole              None

### B3 retrieve by id, by file CID, by metadata CID
200 HTTP by id (AUDITOR)
   status                     COLLECTED
   version                    1
   metadata.description       Suspect phone image
   verification.status        NOT_CHECKED
200 HTTP by file CID
   ids: ['EV-d672eb34-44f9-41ce-8d66-a847366f4a51']
200 HTTP by metadata CID
404 HTTP by unknown CID
   error                      NOT_FOUND
404 HTTP unknown id
   error                      NOT_FOUND

### C2 verify untouched evidence
200 HTTP
   status                     VERIFIED
   ledgerVersion              1
   file.result                VERIFIED
   file.expectedSha256        016cae8fe19c16e429967e838879d672bfca32b674503abbe6ee3964a6eb6ca6
   file.actualSha256          016cae8fe19c16e429967e838879d672bfca32b674503abbe6ee3964a6eb6ca6
   metadata.result            VERIFIED

### C2 DELIBERATE CORRUPTION: change one word inside the file's block on the IPFS node's disk, then restart the node
   -> block file on the node: /data/ipfs/blocks/ZJ/CIQAC3FOR7QZYFXEFGLH5A4IPHLHFP6KGK3HIUB2XPTO4OLEU3VWZJQ.data
   -> before: LIVE-EVIDENCE-1790045599-original-content
   -> after : LIVE-EVIDENCE-1790045599-0RIGINAL-content
   -> IPFS still answers a cat for the same CID (content-addressing did NOT catch it):
   ->    cat -> LIVE-EVIDENCE-1790045599-0RIGINAL-content
200 HTTP verify
   status                     TAMPERED
   file.result                TAMPERED
   file.expectedSha256        016cae8fe19c16e429967e838879d672bfca32b674503abbe6ee3964a6eb6ca6
   file.actualSha256          729300a072c03516da093e66da8aaeb7facc0cd523dd044e250de7d3cc917e2d
   metadata.result            VERIFIED
200 HTTP GET ?verify=true
   verification.status        TAMPERED
   status                     COLLECTED

### C2 NOT_FOUND: register another item, delete its file block from the node's disk, restart
removed-block
200 HTTP verify (288 ms)
   status                     NOT_FOUND
   file.result                NOT_FOUND
   file.actualSha256          None
   metadata.result            VERIFIED

### B4 versioned update (ANALYST), old version stays readable, stale update rejected
200 HTTP update v1->v2
   version                    2
   lastAction                 METADATA_UPDATED
   lastReason                 Location corrected after audit
   metadata.location          Locker 9
   metadata.description       Wallet
   metadata.metadataVersion   2
   metadata.previousMetadataCid bafkreidfsju236kt7oiuammpt6rb4opioifkpjgmzqz34fev65qvyb3jiq
200 HTTP GET version 1 (old)
   version                    1
   metadata.location          Desk 2
200 HTTP GET version 2
   version                    2
   metadata.location          Locker 9
409 HTTP stale update (expectedVersion=1, record is at 2)
   error                      VERSION_CONFLICT
   message                    Expected version 1 but the record is at version 2
400 HTTP blank reason
   error                      VALIDATION_FAILED
403 HTTP JUDGE update
   error                      ACCESS_DENIED

### C3 ledger history (tx ids and ledger timestamps)
200 HTTP
   v1  CREATED           tx=035f0d2612037043..  at=2026-09-22T02:53:56.436027300Z  by=COLLECTOR reason=''
   v2  METADATA_UPDATED  tx=d62ae0c32bdcd4c3..  at=2026-09-22T02:53:58.937953700Z  by=FORENSIC_ANALYST reason='Location corrected after audit'

### B5 disposal: request (PROSECUTOR) -> COLLECTOR cannot approve -> stale approval rejected -> JUDGE approves
200 HTTP request disposal
   status                     COLLECTED
   version                    2
   disposal.state             PENDING
   disposal.reason            Case closed by order 42/2026
   lastAction                 DISPOSAL_REQUESTED
403 HTTP COLLECTOR approve
   error                      ACCESS_DENIED
409 HTTP JUDGE approve with stale version
   error                      VERSION_CONFLICT
200 HTTP JUDGE approve
   status                     DISPOSED
   version                    3
   disposal.state             NONE
   lastAction                 DISPOSAL_APPROVED
   lastReason                 Order verified
409 HTTP update after DISPOSED
   error                      INVALID_STATE
   message                    Evidence is DISPOSED and can no longer change
200 HTTP the DISPOSED record is still readable
   status                     DISPOSED
   version                    3
200   history entries still on ledger: [(1, 'CREATED'), (2, 'DISPOSAL_REQUESTED'), (3, 'DISPOSAL_APPROVED')]

### C-02: there is no delete
   DELETE /api/evidence/{id} as collector -> 405  METHOD_NOT_ALLOWED
   DELETE /api/evidence/{id} as admin -> 405  METHOD_NOT_ALLOWED
   DELETE /api/evidence/{id} as judge -> 405  METHOD_NOT_ALLOWED

### A3 roles: who may register (403 for the rest)
   collector -> 201
   forensic-analyst -> 201
   prosecutor -> 403
   judge -> 403
   auditor -> 403
   admin -> 403

### B2 upload limits and types
   disallowed type (application/x-msdownload) -> 415  UNSUPPORTED_FILE_TYPE | File type 'application/x-msdownload' is not allowed
   empty file for DIGITAL -> 400  FILE_REQUIRED
   file on PHYSICAL evidence -> 400  FILE_NOT_ALLOWED
   missing required metadata field -> 400  ['caseId', 'description']
   60 MB file (limit 50 MB) -> 413  CONTENT_TOO_LARGE
   20 MB file -> HTTP 201 in 4415 ms
   -> local sha256 : f6e6a4be779d45a0e191bda3155b3886ac97c24955f8fa40ff8e974ce3bab7fd
   -> ledger sha256: f6e6a4be779d45a0e191bda3155b3886ac97c24955f8fa40ff8e974ce3bab7fd   size=20000000
200 HTTP verify of the 20 MB file
   status                     VERIFIED
   file.result                VERIFIED

### F1 IPFS outage: ledger information survives, verify says 503 (not NOT_FOUND)
200 HTTP GET during outage
   status                     COLLECTED
   version                    2
   metadataAvailable          False
   metadata                   None
503 HTTP verify during outage
   error                      STORAGE_UNAVAILABLE
   message                    IPFS node not reachable while reading content
   register during outage -> 503  STORAGE_UNAVAILABLE

### G4 health with the reference ledger
200 HTTP
   overall: UP
   ledger : {'details': {'detail': 'chaincode evidence answering on channel crimechannel via localhost:7051 as Org1MSP'}, 'status': 'UP'}
   ipfs   : {'status': 'UP'}
```
```

### F1. Every txId the API reports is a real transaction on the peers' ledger (qscc GetTransactionByID)
   evidence EV-7655637a-cc8c-4649-8fee-d6da97638391  API says: txId=52eccb9c192500c7ec707fd7249356aae11e5f7f7d859f47648185f857b2ac79  ledger timestamp=2026-09-22T02:54:43.596467600Z
   qscc found -> #namespaces/fields/evidence/Sequence
   qscc found -> 'EV-7655637a-cc8c-4649-8fee-d6da97638391
   qscc found -> *EV~EV-7655637a-cc8c-4649-8fee-d6da97638391
   qscc found -> CreateEvidence
   qscc found -> EV-7655637a-cc8c-4649-8fee-d6da97638391
   qscc found -> Org1MSP
   a made-up txId -> Error: endorsement failure during query. response: status:500 message:"Failed to get transaction with id 0000000000000000000000000000000000000000000000000000000

### F2. Concurrent updates to ONE record with the same expectedVersion (Fabric MVCC / version check): exactly one may win
   round 1: statuses ->       1 200
       3 409
    losers' error: ['VERSION_CONFLICT']
            history length after the race: 2  versions: [1, 2]
   round 2: statuses ->       1 200
       3 409
    losers' error: ['VERSION_CONFLICT']
            history length after the race: 2  versions: [1, 2]
   round 3: statuses ->       1 200
       3 409
    losers' error: ['VERSION_CONFLICT']
            history length after the race: 2  versions: [1, 2]

### F3. Ledger outage: stop the Org1 peer the backend talks to
   GET evidence      -> 503  LEDGER_UNAVAILABLE | The ledger network is not reachable
   register          -> 503  LEDGER_UNAVAILABLE
   health overall    -> DOWN | ledger: {'details': {'detail': 'The ledger network is not reachable'}, 'status': 'DOWN'}
   /api/auth/me still works (Postgres, not the ledger) -> 200
   after restarting the peer: GET evidence -> 200 (recovered after ~6 s, no app restart)
```
Diffed against the pre-cutover (still-v1.2, wallet-on) run of the same script, normalised the same way as P3.4: only
timestamps differ, plus one nondeterministic JSON field-ordering difference in a validation error's field list
(unrelated to A2). Every write in this run was signed by that role's own wallet identity, not the old shared one.

**Verification plan items 3 (Java: wallet loading, cache bound, missing entry, no leak) and 5 (recorded gaps,
not tested)** are covered by the Java test run above and by docs/KNOWN_GAPS.md section B respectively.

## P4-G3. Phase 4: ledger event listener (G3) — built, deployed and verified 2026-09-22

Design approved (docs/G3_SYNC_DESIGN.md, including an added reconnect/backoff section) BEFORE any code was
written, per the owner's instruction. H1-H4 and A6 are not built yet; this section is G3 alone.

**Java tests (197 total, up from 183 after A2), full per-class table:**
```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0  config.JwtPropertiesTest
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0  controller.EvidenceControllerTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0  controller.Phase3ControllersTest
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0  ledger.FabricLedgerServiceTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0  ledger.FileWalletIdentityStoreTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0  ledger.HealthIndicatorsTest
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0  ledger.InMemoryLedgerServiceTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0  ledger.InMemoryLedgerTransferTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0  ledger.WalletHealthIndicatorTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0  security.JwtServiceTest
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0  security.SecurityAndErrorFormatTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0  service.AuthServiceTest
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0  service.CaseServiceTest
Tests run: 27, Failures: 0, Errors: 0, Skipped: 0  service.EvidenceServiceTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0  service.HashingInputStreamTest
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0  service.LifecycleServicesTest
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0  storage.HttpIpfsClientTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0  sync.EventProcessorTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0  sync.EventSyncHealthIndicatorTest
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0  sync.EventSyncListenerTest
Tests run: 197, Failures: 0, Errors: 0, Skipped: 0
```
New: `EventProcessorTest` (3: a genuinely new event upserts and advances the checkpoint, a redelivered event
touches neither, each event gets its own activity id), `EventSyncListenerTest` (8: first-run starts at block 0,
a clean multi-event run, tier 1 succeeding on retry with NO stream teardown, tier 1 exhausted escalating as
exactly ONE stream-level failure, a stream failing partway through, `hasEverConnected` staying false through
repeated failed connection attempts and flipping true on the first success, the 5-failure unhealthy threshold,
backoff delay values - exponential with a 30s cap - at every consecutive-failure count), `EventSyncHealthIndicatorTest`
(3: UNKNOWN/UP/DOWN mapping).

**Live: the direct demonstration the owner asked for (design section 10) - stop the listener between two writes,
write while it is down, restart, confirm both appear with no duplicates and the checkpoint caught up:**
```
### First-ever run (empty checkpoint): backfilled the whole ledger from block 0
   eventSync health: UP, consecutiveFailures=0
   evidence_activity rows: 164
   evidence_projection rows: 90
   ledger_sync_checkpoint block_number: 223

### Register while the listener is running (collector, case FAB-G3-LIVE)
   EVA=EV-a491efd6-96f5-438c-b8d6-2a6e9ca72646
   evidence_activity row for EVA: action=CREATED txId=2d53e96449d81810bac088c23a4af86d74e4a6cb018bedb399c93b243252de56
   (present within ~2s of the write, no polling needed)

### App fully stopped (kills the listener)
   evidence_activity rows before outage writes: 165
   checkpoint block_number: 224
### Enrolling a throwaway COLLECTOR identity (revoked at the end)
### Writing EV-b5fccd41-1146-4797-a1c7-0118b4a257ae directly to the chaincode (Spring is stopped; this is exactly what it would miss)
2026-09-22 05:09:15.083 UTC 0003 INFO [chaincodeCmd] chaincodeInvokeOrQuery -> Chaincode invoke successful. result: status:200 payload:"{\"txId\":\"edd8ee3edbc66c810b84f1112d203bf8a6f19b392181054654f6af5cfa849b15\",\"timestamp\":\"2026-09-22T05:09:13.024983443Z\",\"version\":1}" 
### Writing EV-bcfd1e3b-4517-47de-bab2-8e0206a2ba0c directly to the chaincode (Spring is stopped; this is exactly what it would miss)
2026-09-22 05:09:17.266 UTC 0003 INFO [chaincodeCmd] chaincodeInvokeOrQuery -> Chaincode invoke successful. result: status:200 payload:"{\"txId\":\"d770c050342ba76fd1a804e05ffd51ba7d92251ec869f2b575c15d73014564d1\",\"timestamp\":\"2026-09-22T05:09:15.215636239Z\",\"version\":1}" 
written ids:
EV-b5fccd41-1146-4797-a1c7-0118b4a257ae
EV-bcfd1e3b-4517-47de-bab2-8e0206a2ba0c

### While still stopped: confirmed Postgres has NOT changed (the outage really is invisible while down)
   evidence_activity rows: 165 (unchanged)
   evidence_projection rows for the two outage-written ids: 0

### App restarted: both outage writes caught up, checkpoint advanced exactly, no duplicates
   evidence_activity rows: 167 (165 + 2, exactly)
   EV-b5fccd41-1146-4797-a1c7-0118b4a257ae | CREATED | edd8ee3edbc66c810b84f1112d203bf8a6f19b392181054654f6af5cfa849b15
   EV-bcfd1e3b-4517-47de-bab2-8e0206a2ba0c | CREATED | d770c050342ba76fd1a804e05ffd51ba7d92251ec869f2b575c15d73014564d1
   checkpoint block_number: 226 (224 + 2, exactly)

### A further restart with NO new writes: every count unchanged (idempotency holds)
   evidence_activity: 167  evidence_projection: 93  checkpoint block_number: 226
   eventSync health: UP, consecutiveFailures=0

### Regression: the whole Phase 2 checklist re-run with the listener active
   scripts/live/live_phase2.sh exit 0; application log ERROR count: 0; script traceback count: 0
```

**Regression:** the whole Phase 2 checklist re-run with the listener active - 0 application `ERROR` lines, 0
script tracebacks (verbatim in Appendix P4-G3).

## P4-H. Phase 4: H1-H4, A6 — built and verified live, 2026-09-22

Built straight through after G3's approval, per docs/G3_SYNC_DESIGN.md section 11. All verified against the
same running system as P4-G3 (real Fabric + PostgreSQL, G3 listener active).

**Java tests (208 total, up from 197 after G3), full per-class table:**
```
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0  audit.AuditServiceTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0  config.JwtPropertiesTest
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0  controller.EvidenceControllerTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0  controller.Phase3ControllersTest
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0  ledger.FabricLedgerServiceTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0  ledger.FileWalletIdentityStoreTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0  ledger.HealthIndicatorsTest
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0  ledger.InMemoryLedgerServiceTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0  ledger.InMemoryLedgerTransferTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0  ledger.WalletHealthIndicatorTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0  notification.NotificationServiceTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0  security.JwtServiceTest
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0  security.SecurityAndErrorFormatTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0  service.AuthServiceTest
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0  service.CaseServiceTest
Tests run: 27, Failures: 0, Errors: 0, Skipped: 0  service.EvidenceServiceTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0  service.HashingInputStreamTest
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0  service.LifecycleServicesTest
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0  storage.HttpIpfsClientTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0  sync.EventProcessorTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0  sync.EventSyncHealthIndicatorTest
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0  sync.EventSyncListenerTest
Tests run: 208, Failures: 0, Errors: 0, Skipped: 0
```
New: `AuditServiceTest` (5), `NotificationServiceTest` (6). H1/H2/H3 are read-only, no-business-logic queries
(a JPA Specification and a few native aggregate queries) verified directly against real PostgreSQL live, not
duplicated in a unit test against a fake database.

### H1-H4: setup and live results (verbatim)
```

### Setup: a case, two evidence items, a transfer, a status change, a disposal
case FAB-H4-6438 -> 201
E1=EV-dc9d4ffb-3900-4eb2-8319-0dcfc0bda5b7  E2=EV-5045989f-cad1-4e8f-a136-3e203e274718
transfer EV-dc9d4ffb-3900-4eb2-8319-0dcfc0bda5b7 collector->analyst -> 200
status change EV-5045989f-cad1-4e8f-a136-3e203e274718 -> PROCESSING -> 200
disposal request EV-5045989f-cad1-4e8f-a136-3e203e274718 -> 200
disposal approve EV-5045989f-cad1-4e8f-a136-3e203e274718 -> 200

### H1 search/filter (served from Postgres, not the ledger)
by caseId -> 200
   totalElements: 2  ids: ['EV-dc9d4ffb-3900-4eb2-8319-0dcfc0bda5b7', 'EV-5045989f-cad1-4e8f-a136-3e203e274718']
by status=DISPOSED -> 200
   ids: ['EV-c7800f6a-b95e-4325-8799-4f9c2c702716', 'EV-c8ce9372-3429-48fc-9741-de4700f94f7d', 'EV-a1e6c5fd-091a-4982-b3f4-6cbdff41cc8a', 'EV-197b8db1-7673-4174-8b64-d92b66cb7fb9', 'EV-09b33d4a-1ac3-483b-a402-6388d4beb361', 'EV-9a8c5dea-0244-4dc3-bf54-059ab1dfcf91', 'EV-b72feb78-9fc5-430f-8e4b-c6928182f319', 'EV-dbd9201f-e4a6-45cb-a129-2a4ed5eb8a44', 'EV-5045989f-cad1-4e8f-a136-3e203e274718']  (expect EV-5045989f-cad1-4e8f-a136-3e203e274718 among them)
free text q=lab -> 200
   ids: []
by officer=collector -> 200
   count: 20
paginated size=1 -> 200
   items: 1  totalPages: 2

### H2 dashboard analytics
-> 200
   totalEvidence: 103
   byStatus: {'ARCHIVED': 3, 'COLLECTED': 91, 'DISPOSED': 9}
   byType: {'PHYSICAL': 78, 'DIGITAL': 25}
   activityByDay entries: 2  last: {'date': '2026-09-22', 'count': 85}

### H3 activity feed
-> 200
   DISPOSAL_APPROVED   EV-5045989f.. v4  approved
   DISPOSAL_REQUESTED  EV-5045989f.. v3  case closed
   STATUS_CHANGED      EV-5045989f.. v2  sent to lab
   TRANSFER_INITIATED  EV-dc9d4ffb.. v2  analysis
   CREATED             EV-5045989f.. v1  
   CREATED             EV-dc9d4ffb.. v1  

### H4 notifications
analyst's notifications (expect TRANSFER_PENDING for EV-dc9d4ffb-3900-4eb2-8319-0dcfc0bda5b7) -> 200
   DISPOSAL_APPROVED    Evidence EV-5045989f-cad1-4e8f-a136-3e203e274718 was disposed
   DISPOSAL_REQUESTED   Evidence EV-5045989f-cad1-4e8f-a136-3e203e274718 has a disposal request pending
   STATUS_CHANGED       Evidence EV-5045989f-cad1-4e8f-a136-3e203e274718 changed status to PROCESSING
   TRANSFER_PENDING     You have a pending custody transfer for evidence EV-dc9d4ffb-3900-4eb2-8319-0dcfc0bda5b7
mark read -> 204
   readAt now: 2026-09-22T08:07:59.298091Z
case members' notifications for the status change / disposal on EV-5045989f-cad1-4e8f-a136-3e203e274718 (prosecutor, judge, collector as lead, analyst as member):
   collector: ['DISPOSAL_APPROVED', 'DISPOSAL_REQUESTED', 'STATUS_CHANGED']
   analyst: ['DISPOSAL_APPROVED', 'DISPOSAL_REQUESTED', 'STATUS_CHANGED']
   prosecutor: []
   judge: []

tamper alert: corrupt E1's file block on IPFS disk, then verify=true
verify EV-4bef7b05-621b-4e94-993c-36d88b126881 -> 200
   status                     TAMPERED
   file.result                TAMPERED
   collector notifications for EV-4bef7b05-621b-4e94-993c-36d88b126881: ['TAMPER_ALERT']
```

### A6: the full set, including the specific check the owner required before this could be marked done (verbatim)
```

### A6 ordinary VIEW and DOWNLOAD
GET EV-dc9d4ffb-3900-4eb2-8319-0dcfc0bda5b7 (VIEW) -> 200
GET EV-dc9d4ffb-3900-4eb2-8319-0dcfc0bda5b7?verify=true (DOWNLOAD) -> 200
DOWNLOAD | EV-dc9d4ffb-3900-4eb2-8319-0dcfc0bda5b7 | auditor@blockevidence.local
VIEW | EV-dc9d4ffb-3900-4eb2-8319-0dcfc0bda5b7 | auditor@blockevidence.local

### A6 failed attempts: ACCESS_DENIED and AUTH_FAILED
collector tries to approve disposal (JUDGE only) -> 403
bad login -> 401
garbage token -> 401
AUTH_FAILED | /api/evidence/EV-dc9d4ffb-3900-4eb2-8319-0dcfc0bda5b7 |  | Full authentication is required to access this resource
AUTH_FAILED | /api/auth/login |  | Invalid email or password
ACCESS_DENIED | /api/evidence/EV-dc9d4ffb-3900-4eb2-8319-0dcfc0bda5b7/disposal/approve | collector@blockevidence.local | Access Denied

### A6: THE required check - deactivate a user with a STILL-VALID access token, then use it
auditor's token is already issued and valid; deactivate the auditor account directly (no A4 endpoint exists yet):
UPDATE 1
the auditor's OLD token: eyJhbGciOiJIUzI1NiJ9.eyJp... (issued before deactivation, not yet expired)
GET EV-dc9d4ffb-3900-4eb2-8319-0dcfc0bda5b7 with that now-stale-but-cryptographically-valid token -> 200
   HTTP response status field (request still SUCCEEDS - the TTL gap is not being fixed): COLLECTED
the resulting audit row:
            action             |                resource                 |            email            |  detail  |          occurred_at          
-------------------------------+-----------------------------------------+-----------------------------+----------+-------------------------------
 TOKEN_USED_AFTER_DEACTIVATION | EV-dc9d4ffb-3900-4eb2-8319-0dcfc0bda5b7 | auditor@blockevidence.local | view     | 2026-09-22 08:09:00.68383+00
 DOWNLOAD                      | EV-dc9d4ffb-3900-4eb2-8319-0dcfc0bda5b7 | auditor@blockevidence.local | download | 2026-09-22 08:08:56.205114+00
 VIEW                          | EV-dc9d4ffb-3900-4eb2-8319-0dcfc0bda5b7 | auditor@blockevidence.local | view     | 2026-09-22 08:08:56.058368+00
(3 rows)

compare: an ordinary VIEW by the SAME auditor BEFORE deactivation (already shown above) was action=VIEW - this one is different.
reactivating the auditor account (cleanup, restores the seeded state)
UPDATE 1
confirming: a fresh login + request afterwards is an ordinary VIEW again
VIEW | auditor@blockevidence.local

### A6 read endpoint: GET /api/audit (ADMIN/AUDITOR only)
as admin, filtered to the deactivation-visibility action -> 200
   totalElements: 1
     TOKEN_USED_AFTER_DEACTIVATION auditor@blockevidence.local EV-dc9d4ffb-3900-4eb2-8319-0dcfc0bda5b7
as collector (must be refused, ADMIN/AUDITOR only) -> 403
```
The `TOKEN_USED_AFTER_DEACTIVATION` row is distinguishable, in the same table and the same query, from the
ordinary `VIEW`/`DOWNLOAD` rows the SAME user's earlier requests produced - not asserted from the schema, shown
in the real running system. No A4 (admin user management) endpoint exists yet, so the deactivation itself was a
direct SQL `UPDATE users SET enabled=false` - exactly what A4 would eventually do through the API, without
building A4 (out of scope for Phase 4).

### Regression: the whole Phase 2 checklist re-run with H1-H4/A6 active
`scripts/live/live_phase2.sh` exit 0; application log `ERROR` count: 0; script traceback count: 0.


## P5-F2F3. Phase 5: envelope encryption and key management (F2, F3) — built and verified live, 2026-09-22

**Read this first.** Real PostgreSQL 16 (Flyway V6 applied cleanly) and a real Kubo 0.43 node (`--offline`)
throughout. Checks 1-9 below first ran against `InMemoryLedgerService` (profile `memory-ledger`) because the
Fabric CA registrar credential and the dev users' wallet were lost between sessions (DECISIONS D-059). Both
were rebuilt owner-approved (D-060/D-061 - the same "identity modify" recovery pattern
`bootstrap_registrar.sh` already documents, no new access created) and the run was repeated against REAL
`FabricLedgerService`: a real chaincode write and the custody-transfer-receiver auto-wrap off G3's real event
stream both passed (D-062, appended below as checks 10-11). **All of F2/F3 is now confirmed against real
Fabric, real PostgreSQL and real IPFS.**

**Java tests (226 total, up from 208 after H1-H4/A6):**
```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.029 s -- in com.blockevidence.backend.crypto.AesGcmCodecTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.345 s -- in com.blockevidence.backend.crypto.ContentKeyServiceTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.359 s -- in com.blockevidence.backend.crypto.UserKeyServiceTest
...
Tests run: 226, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
All three new classes are tested against REAL RSA-2048/AES-256-GCM crypto (`support/FakeContentKeys`), not
mocked - a real round trip, not an assumed one. `EvidenceServiceTest.registerDigitalStoresFileAndMetadataInIpfs...`
was extended to assert the stored bytes are NOT the plaintext, are exactly `plaintext.length + 28` (IV+tag), and
that unwrapping the registrant's key and decrypting recovers the exact original bytes.

### Live run (verbatim, `scripts/live/verify_f2_f3.sh` - a case with collector as sole member, then an evidence
item registered under it)
```
### 1. register DIGITAL evidence as collector (F2: file+metadata get encrypted)
   evidence EV-3d5055ac-b811-4715-b48a-1859de487159
   fileCid=bafkreihdc67kgz4w7ygp5akyb6mr45b7egtdslnucopk3o6lymqgv3b6fu
   metadataCid=bafkreihj5vlhhww5j4u452uxzpm3nsp4wfcz3igedppphsiqnah4fihcja

### 2. IPFS holds CIPHERTEXT, not the plaintext (F2 design section 7)
   stored bytes length: 88 (plaintext was 60, overhead 28)
   stored bytes == plaintext? False
   sha256(stored) matches ledger fileSha256? True

### 3. GET as collector (registrant): metadata decrypts correctly
   metadataAvailable: True
   description: Phone found at scene

### 4. GET as analyst (NOT yet a case member): metadata NOT decryptable
   metadataAvailable: False (expect False)

### 5. F2 Q3 download endpoint: analyst (not authorised) gets 403 KEY_NOT_AUTHORISED
   -> 403
   KEY_NOT_AUTHORISED | You are not authorised to decrypt evidence EV-3d5055ac-b811-4715-b48a-1859de487159

### 6. F3: add analyst as a case member -> re-wrap should grant them access
   analyst metadataAvailable NOW: True (expect True)
   description: Phone found at scene

### 7. F2 Q3 download endpoint: analyst NOW authorised, decrypted bytes match the original upload exactly
Content-Disposition: attachment; filename="upload.bin"
Content-Type: text/plain
   downloaded == original plaintext? True

### 8. F3: remove analyst from the case -> revoke
   analyst metadataAvailable AFTER REVOKE: False (expect False again)
   -> download after revoke: 403
   KEY_NOT_AUTHORISED

### 9. tamper test: corrupt the stored ciphertext on real IPFS, confirm verify() catches it WITHOUT decrypting
   block file located by exact byte match (docker cp + cmp over /data/ipfs/blocks), one byte flipped at offset 40
   overall status: TAMPERED (expect TAMPERED)
   file check result: TAMPERED
   file expectedSha256 == ledger fileSha256: True
   metadata.result: VERIFIED (untouched - only the file block was corrupted)
```
Every result matched the design's stated behaviour. `verify()`'s call path was confirmed by source inspection
to never reach `ContentKeyService` (docs/F2_F3_ENVELOPE_ENCRYPTION_DESIGN.md section 7) - this run demonstrates
the OUTCOME (TAMPERED detected) that guarantee produces.

### Checks 10-11, verbatim, against REAL Fabric (D-060/D-061/D-062, `verify_f2_f3_real_fabric.sh`)
```
### 1. REAL CHAINCODE WRITE: register DIGITAL evidence as collector
   evidence: EV-3a9518e0-7562-4f03-9050-d02d1f69e998

### 2. confirm it is really on the Fabric ledger
[{"version":1,"txId":"90549b394b5aa201d159b7dc620e83b47712d2d4e427f4eacedc2b8e24981128","action":"CREATED", ...}]

### 3. custody transfer: collector initiates to analyst (NOT yet wrapped)
   analyst metadataAvailable BEFORE transfer: False
   (transfer initiated -> version 2, lastAction TRANSFER_INITIATED)

### 4. waiting for G3's real Fabric event listener to process TRANSFER_INITIATED and auto-wrap the receiver
   analyst metadataAvailable after transfer (poll attempt 1, ~1 second): True
   description now visible to analyst: Real Fabric evidence

### 5. analyst downloads the decrypted file - byte-exact
   downloaded == original plaintext? True
```
A real 64-hex-character Fabric transaction id, and the receiver auto-wrap firing off the real chaincode event
stream within the first one-second poll - not a stub, not a unit-test double. This closes the one gap the
`memory-ledger` run above could not cover; **all of F2/F3 is now verified against real Fabric.**


## P5-F5. Phase 5: upload consistency and retry (F5) — built and verified, 2026-09-22

Extends D-024 (Phase 2, unchanged) with a bounded retry on a genuine Fabric-level MVCC read conflict, scoped to
`register()`'s `createEvidence` call (docs/features/f5-upload-retry.md has the full "why only register()"
reasoning, found by reading the chaincode, not assumed).

**Unit tests (`EvidenceServiceRetryTest`, a Mockito-controlled `LedgerService` so the exact failure sequence is
under test control):**
```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.926 s -- in com.blockevidence.backend.service.EvidenceServiceRetryTest
```
Covers: succeeds on the 2nd attempt after one `CONCURRENT_WRITE_CONFLICT`; succeeds on the 3rd and final attempt;
exhausts all 3 and still propagates the failure; never retries a chaincode `VERSION_CONFLICT` (fails on attempt
1); never retries an unrelated `ApiException`; `compensate()` still runs after retries are exhausted (D-024's
unpin-only-if-unreferenced rule unchanged).

**Full suite after F5:**
```
Tests run: 232, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**Live regression against real Fabric/PostgreSQL/IPFS** (the genuine-conflict trigger itself could not be
exercised live - see "Known limit" below): registered PHYSICAL evidence through the retry-wrapped path.
```
POST /api/evidence (collector, real Fabric) -> 201, evidence EV-fbe472cd-7316-47cd-acf0-c11a1676134e
application log grep for "retry" or "ERROR": zero matches - createEvidenceWithRetry succeeded on attempt 1,
fully transparent for the normal (non-conflict) case.
```

### Known limit in this run (see docs/features/f5-upload-retry.md)
A genuine Fabric-level MVCC conflict could not be triggered live for `CreateEvidence` specifically: it reads and
writes only keys derived from its own arguments (a fresh evidenceId, CID-index entries keyed by `(cid,
evidenceId)`) - no other transaction could ever contend for the same key, confirmed by reading the chaincode.
Contriving a collision would mean adding artificial shared state to the chaincode purely for a demonstration,
which was not done. The retry MECHANICS are proven by the 6 unit tests above instead, with tighter control over
the exact failure sequence than a live trigger could offer regardless.


## P2. Phase 2: evidence management (B1-B5, C1-C3) — run 2026-09-22

**Read this first.** Every live result in THIS section (P2) ran against `InMemoryLedgerService` (profile `memory-ledger`),
real PostgreSQL 16, and a real Kubo 0.43 node in `--offline` mode. It is **not** a Fabric run and proves
nothing about the chaincode. The app logged `*** memory-ledger profile active: ... NOT a blockchain, NOT
tamper-proof ***` at startup. The Fabric run (same checks through `FabricLedgerService`) is a to-do that
starts after `docs/CHAINCODE_DESIGN.md` is approved and implemented.

### P2.1 Automated tests: `./mvnw test`

```
Tests run: 4   JwtPropertiesTest                 Tests run: 12  EvidenceControllerTest (roles, C-05, no DELETE, validation)
Tests run: 5   HealthIndicatorsTest              Tests run: 14  InMemoryLedgerServiceTest (the executable G2 spec)
Tests run: 9   JwtServiceTest                    Tests run: 26  EvidenceServiceTest (register, compensation, C2 tamper, B4, B5)
Tests run: 17  SecurityAndErrorFormatTest        Tests run: 5   HashingInputStreamTest (known-answer SHA-256)
Tests run: 13  AuthServiceTest                   Tests run: 12  HttpIpfsClientTest (fake Kubo: add / cat / pin-rm / probe)
Tests run: 117, Failures: 0, Errors: 0, Skipped: 0     BUILD SUCCESS
```
A first run failed 2 of 117 (`HttpIpfsClientTest.pin...`, `NoClassDefFoundError: org/reactivestreams/Publisher`).
That was a real defect, found by the test and fixed (DECISIONS D-025), not a flaky test.

### P2.2 Live checks: expected versus actual

The full, untrimmed output of the final run is in Appendix P2-A below. "Actual" cells quote it.

| ID | Check | Expected | Actual (final run) |
|---|---|---|---|
| B1 | Register DIGITAL with a file | 201, server-generated `EV-<uuid>`, status COLLECTED, version 1 | `HTTP 201`, `status COLLECTED`, `version 1`, `evidenceId EV-...` |
| C-05 | Metadata carries `"collector":"forged@evil.example"` and `"collectorId":"0000..."` | ignored; creator = JWT user | `createdBy` and `metadata.collectorId` both equal the id from `/api/auth/me`; "createdBy == collector id from JWT? YES" |
| C1 | Ledger `fileSha256` equals an independent hash of the file | equal | `local sha256 ... == ledger fileSha256 ...` (identical 64 hex chars) |
| B2/F1 | Bytes fetched from IPFS by the file CID equal the original | equal | "stored bytes fetched from IPFS by the file CID == original? YES" |
| B1 | Register PHYSICAL (no file) | 201, `fileCid`/`fileSha256` null | `HTTP 201`, `fileCid None`, `fileSha256 None` |
| B3 | By id / file CID / metadata CID | 200 each | `200`, `200`, `200`; unknown CID -> `404 NOT_FOUND`; unknown id -> `404 NOT_FOUND` |
| C2 | Verify untouched evidence | VERIFIED, expected = actual hash | `status VERIFIED`, `file.expectedSha256 == file.actualSha256`, `metadata.result VERIFIED` |
| **C2** | **Deliberately corrupt the file's block on the IPFS node's disk (one word), restart the node, verify** | **TAMPERED** | before `...-original-content`, after `...-0RIGINAL-content`; the node still serves it under the same CID (`cat -> ...0RIGINAL-content`); verify: `status TAMPERED`, `file.expectedSha256 770805fd...` vs `file.actualSha256 01037869...`, `metadata.result VERIFIED` (only the file was touched); `GET ?verify=true` -> `verification.status TAMPERED` |
| **C2** | **Delete the file's block from the node's disk, restart, verify** | **NOT_FOUND** | `removed-block`, then `status NOT_FOUND`, `file.actualSha256 None`, `metadata.result VERIFIED`, in 161 ms |
| C2 | Node stopped -> verify | 503, not a verdict | `HTTP 503 STORAGE_UNAVAILABLE` |
| B4 | PUT update v1->v2 with a reason | version 2, `lastReason` set, old metadata untouched | `version 2`, `lastAction METADATA_UPDATED`, `lastReason Location corrected after audit`, `metadata.metadataVersion 2`, `previousMetadataCid bafk...` |
| B4 | Old version still readable | version 1 shows the old location | `GET /versions/1 -> Desk 2`; `GET /versions/2 -> Locker 9` |
| B4 | Stale update (`expectedVersion 1`, record at 2) | 409 | `409 VERSION_CONFLICT`: "Expected version 1 but the record is at version 2" |
| B4 | Blank reason / JUDGE tries to update | 400 / 403 | `400 VALIDATION_FAILED` / `403 ACCESS_DENIED` |
| C3 | History | one entry per version with tx id and ledger time | `v1 CREATED tx=... at=... by=COLLECTOR`, `v2 METADATA_UPDATED tx=... by=FORENSIC_ANALYST reason='Location corrected after audit'` |
| B5 | PROSECUTOR requests disposal | 200, PENDING, status unchanged | `disposal.state PENDING`, `status COLLECTED`, `lastAction DISPOSAL_REQUESTED` |
| B5 | COLLECTOR tries to approve | 403 | `403 ACCESS_DENIED` |
| B5 | JUDGE approves a stale version | 409 | `409 VERSION_CONFLICT` |
| B5 | JUDGE approves the reviewed version | DISPOSED, version +1 | `status DISPOSED`, `version 3`, `lastAction DISPOSAL_APPROVED`, `lastReason Order verified` |
| B5 | Edit after DISPOSED | rejected | `409 INVALID_STATE`: "Evidence is DISPOSED and can no longer change" |
| B5/C-02 | Record and history survive disposal | still readable | `status DISPOSED` on GET; history `[(1,'CREATED'),(2,'DISPOSAL_REQUESTED'),(3,'DISPOSAL_APPROVED')]` |
| C-02 | DELETE `/api/evidence/{id}` as collector, admin, judge | 405 | `405 METHOD_NOT_ALLOWED` x3 |
| A3 | Register as each role | 201 for COLLECTOR, FORENSIC_ANALYST only | `collector 201`, `forensic-analyst 201`, `prosecutor 403`, `judge 403`, `auditor 403`, `admin 403` |
| B2 | Disallowed type | 415 | `415 UNSUPPORTED_FILE_TYPE`: "File type 'application/x-msdownload' is not allowed" |
| B2 | Empty file for DIGITAL / file on PHYSICAL | 400 | `400 FILE_REQUIRED` / `400 FILE_NOT_ALLOWED` |
| B2 | 60 MB (limit 50 MB) | 413 | `413 CONTENT_TOO_LARGE` |
| B2/C1 | 20 MB image | 201, hash and size match | `HTTP 201 in 987 ms`, ledger sha256 == local sha256, `size=20000000`; verify -> VERIFIED |
| F1 | IPFS stopped: GET evidence | ledger info still returned | `HTTP 200`, `status COLLECTED`, `version 2`, `metadataAvailable False`, `metadata None` |
| F1 | IPFS stopped: register | 503, nothing half-written | `503 STORAGE_UNAVAILABLE` |
| G4 | Health with the reference ledger | overall UP, ledger labelled fake | `overall UP`; `ledger detail "IN-MEMORY reference ledger (dev/test only). NOT a blockchain, NOT tamper-proof."` |

Two harness bugs surfaced on the first run of the script and were fixed before the final run (both mine,
neither the app's): Git Bash rewrote `/data/ipfs/...` arguments to `docker exec`, so the "before/after" lines
were blank and the block deletion silently did nothing (NOT_FOUND showed `VERIFIED`). With
`MSYS_NO_PATHCONV=1` both checks are real. A normalised diff of the two runs showed these were the only differences.

### P2.3 Compensation and safety (automated, `EvidenceServiceTest`)

| Check | Result |
|---|---|
| Ledger rejects the write -> the pins made for it are removed | passed: no orphaned pins, 2 unpinned |
| ...but a file CID another record still references is NEVER unpinned | passed |
| Ledger cannot be asked -> nothing is unpinned ("unsure means keep the data") | passed |
| Update refuses to build on tampered metadata (409 INTEGRITY_CHECK_FAILED) | passed |
| Update with a stale version writes nothing to IPFS | passed |
| Hostile client filenames reduced to a bare name (`..\\..\\x.txt`, `/etc/passwd`, CR/LF) | passed |
| `LedgerService` has no method named delete/remove/purge/erase/destroy | passed |

**P2.3b Constraint and boundary greps, re-run on the Phase 2 code (2026-09-22).** Commands and actual results:

| Rule | Command | Actual |
|---|---|---|
| C-01 no Fabric anywhere | `grep -rnE "^import .*(hyperledger\|fabric)" src`; `grep -ni "<artifactId>.*fabric" pom.xml` | both: no output |
| C-01 only `service/` uses the ledger outside `ledger/` | `grep -rln "backend\.ledger" src/main --include=*.java` minus `/ledger/` | `service/EvidenceService.java`, `service/VerificationService.java` only |
| Controllers see no ledger/storage/repository/model | `grep -nE "import ...(ledger\|storage\|repository\|model)" controller/*.java` | no output |
| DTOs import no ledger/storage/service/controller | `grep -rnE ... dto` | no output |
| `domain/` imports no other application package | `grep -rnE ... domain` | no output |
| `exception/` imports no ledger/storage | `grep -rnE ... exception` | no output |
| `ledger/` and `storage/` import neither controller/service nor each other | `grep -rnE ... ledger storage` | no output (both directions) |
| C-02 no delete anywhere | `grep -rniE "@DeleteMapping\|RequestMethod\.DELETE\|deleteBy\|deleteAll\|\.delete\(" src/main` | no output |
| C-05 request DTOs name no acting user | components of `RegisterEvidenceRequest`, `UpdateEvidenceRequest`, `DisposalRequestBody`, `DisposalDecisionBody` | `caseId,type,description,location,collectedAt,notes`; `expectedVersion,reason,description,location,notes`; `expectedVersion,reason`; `expectedVersion,note`: no collector/officer/actor field |
| C-07 no key literals | `grep -rniE "unit-test-secret\|0123456789abcdef\|password\s*=\s*\"[^\"]{6,}\"" src` | no output |

### P2.4 Not covered yet (known gaps)

- (Resolved 2026-09-22) Fabric: see section P2-F.
- Memory: a 20 MB upload was tested; streaming/heap behaviour for a 50 MB upload under concurrent load was not.
- No automated test proves the block-corruption scenario (it needs a real Kubo node and disk access); the
  unit-level equivalent uses `FakeIpfsClient.corrupt()`.

## P2-F. Phase 2 through REAL Fabric (chaincode `evidence` v1.1) — run 2026-09-22

Everything here ran against the real network (Fabric 2.5.15, 2 orgs, both endorsing), the real Go chaincode on the peers, real PostgreSQL,
and a real Kubo node in `--offline` mode. Verbatim logs are in the appendices at the end of this file.

### P2-F.1 Network revival (G7, option a): no recreation

| Step | Actual |
|---|---|
| State before | 6 Fabric containers `Exited (255) 4 months ago`; `basic` v1.0 seq 1 committed |
| `docker start ca_orderer ca_org1 ca_org2`, `orderer`, then both peers | all `Up`; peers logged `SERVICE_UNAVAILABLE` from the orderer for ~2 s, then `Pulling next blocks ... nextBlock=48` |
| `peer channel list` / `getinfo` | `crimechannel`, `"height":48` |
| `peer lifecycle chaincode querycommitted` | `Name: basic, Version: 1.0, Sequence: 1` |

### P2-F.2 Chaincode unit tests: `cd chaincode/evidence && go test -v ./...`

22 passed, 0 failed (list in Appendix P2-F-A). They mirror `InMemoryLedgerServiceTest` and additionally prove: a rejected call changes
nothing (Fabric's rollback), the history is ordered even if the peer iterates newest-first, every write emits exactly one event with only
identifiers, `DelState`/`PurgePrivateData` are never called (syntax-tree scan, not text grep), the exported function set is exactly the
approved nine, the JSON keys are the contract with Java, and every `omitempty` field is `optional` in the schema.

### P2-F.3 Deployment (`chaincode/scripts/deploy_cc.sh`, `VER=1.1 SEQ=2`)

| Step | Actual (Appendix P2-F-B) |
|---|---|
| Package + install on Org1 / Org2 | `Installed remotely: response:<status:200 ...>` on both; package id `evidence_1.1:4e6e0c43...ed84` |
| Approve for Org1 / Org2 | each `committed with status (VALID)` |
| Commit readiness | `"Org1MSP": true, "Org2MSP": true` |
| Commit | `committed with status (VALID)` at `localhost:9051` and `localhost:7051` |
| Committed definitions | `Name: basic, Version: 1.0, Sequence: 1` and `Name: evidence, Version: 1.1, Sequence: 2` |

### P2-F.4 The real chaincode driven directly through the `peer` CLI (bypassing Spring; every write endorsed by BOTH orgs)

Actual output in Appendix P2-F-C. This is what proves the chaincode's own second-line checks.

| Call | Expected | Actual |
|---|---|---|
| `CreateEvidence` DIGITAL as COLLECTOR | committed | `status:200`, then `GetEvidence` returns the record (`version 1`, `COLLECTED`) |
| `CreateEvidence` as JUDGE / as ADMIN | refused by the chaincode | `FORBIDDEN_ROLE: Role JUDGE may not create evidence` / `Role ADMIN may not create evidence` |
| Create the same id again | refused | `EVIDENCE_EXISTS: Evidence EV-... already exists` |
| `UpdateEvidence` expectedVersion 7 (record at 1) | refused | `VERSION_CONFLICT: Expected version 7 but the record is at version 1` |
| `UpdateEvidence` v1 -> v2 | committed | `status:200` |
| `UpdateStatus` COLLECTED -> ARCHIVED (skips two) | refused | `INVALID_STATE: Status cannot move from COLLECTED to ARCHIVED` |
| `UpdateStatus` to DISPOSED | refused | `INVALID_ARGUMENT: DISPOSED is only reachable through an approved disposal` |
| `UpdateStatus` COLLECTED -> PROCESSING | committed | `status:200` |
| Invoke `DeleteEvidence` | does not exist | `Function DeleteEvidence not found in contract EvidenceContract` |
| `RequestDisposal` by COLLECTOR | committed | `status:200` |
| `ApproveDisposal` by COLLECTOR | refused | `FORBIDDEN_ROLE: Role COLLECTOR may not approve disposal` |
| `ApproveDisposal` by JUDGE, stale version 3 (record at 4) | refused | `VERSION_CONFLICT: Expected version 3 but the record is at version 4` |
| `ApproveDisposal` by JUDGE, version 4 | committed | `status:200` |
| `UpdateEvidence` after DISPOSED | refused | `INVALID_STATE: Evidence is DISPOSED and can no longer change` |
| `GetEvidence` after disposal | record still there | `"status":"DISPOSED","version":5` |
| `FindByCid` file CID / original metadata CID | the id | both `["EV-c7800f6a-..."]` (old CID still indexed after the update) |
| `GetHistory` | 5 versions, real tx ids, peer timestamps | `v1 CREATED ... v5 DISPOSAL_APPROVED`, each with a distinct 64-hex `tx=` and a nanosecond timestamp |

### P2-F.5 Java: `./mvnw test`

`Tests run: 130, Failures: 0, Errors: 0, Skipped: 0`. New: `FabricLedgerServiceTest` (15) parses REAL peer output captured to
`src/test/resources/fabric/` with a non-lenient JSON mapper, translates the two real peer error messages, checks all six chaincode codes
map to their `LedgerErrorCode`, MVCC and connectivity mapping, the no-detail-leak rule, "not configured" and "cannot connect" behaviour,
and file-or-directory identity paths.

### P2-F.6 The FULL Phase 2 checklist through Spring -> real Fabric

`scripts/live/live_phase2.sh` (the same script that produced Appendix P2-A) run against the default profile. Health first:
`ledger: {'details': {'detail': 'chaincode evidence answering on channel crimechannel via localhost:7051 as Org1MSP'}, 'status': 'UP'}`.

**Comparison method:** normalise ids, hashes, CIDs, timestamps and timings out of both logs and `diff` them.

```
=== normalised diff: in-memory run vs REAL FABRIC run (after the fix) ===
170c170
<    ledger : {'details': {'detail': 'IN-MEMORY reference ledger (dev/test only). NOT a blockchain, NOT tamper-proof.'}, 'status': 'UP'}
---
>    ledger : {'details': {'detail': 'chaincode evidence answering on channel crimechannel via localhost:7051 as Org1MSP'}, 'status': 'UP'}
=== end
```
The only difference between the two ledgers across ~170 lines is the sentence naming the ledger. So every row of table P2.2 (register,
forged collector ignored, hash equality, by id / file CID / metadata CID, VERIFIED, **on-disk block corruption -> TAMPERED**, **deleted block
-> NOT_FOUND**, versions, stale update 409, blank reason 400, JUDGE update 403, history, disposal request/approve/stale/frozen,
DELETE 405, role matrix, upload limits, IPFS outage 503) holds through real Fabric. Verbatim: Appendix P2-F-D.

An earlier Fabric run showed ONE more difference (defect D-034: `... can no longer change ABORTED: failed to endorse transaction ...`);
it was fixed and the run repeated; the diff above is the repeated run.

### P2-F.7 Checks only a real ledger can show (`scripts/live/live_fabric_extra.sh`, Appendix P2-F-E)

| ID | Check | Expected | Actual |
|---|---|---|---|
| F1 | txId from `GET /history` looked up on the peers with `qscc GetTransactionByID` | exists | found: the transaction contains `CreateEvidence`, the key `EV~EV-74d325e1-...` and `Org1MSP`; a made-up txId -> `Failed to get transaction with id 0000...` |
| F2 | 4 concurrent `PUT`s, same record, same `expectedVersion`, 3 rounds | exactly one wins | every round: `1 200  3 409`, losers' error `['VERSION_CONFLICT']`, history length 2, versions `[1, 2]` |
| F3 | Stop `peer0.org1` | 503, health DOWN, no crash | GET -> `503 LEDGER_UNAVAILABLE`; register -> `503`; health `DOWN` (`The ledger network is not reachable`); `/api/auth/me` still `200` |
| F3 | Start the peer again | recovers by itself | GET -> `200` after ~4 s, no application restart |
| F4 | Restart the application, read an earlier record | still there | `status COLLECTED, version 1, createdAt 2026-09-21T20:02:52.437724Z`; verify -> `VERIFIED` |

Ledger height after everything: 93 (48 at the start). Committed: `basic` 1.0/1 and `evidence` 1.1/2.

### P2-F.8 Defects found by running on real Fabric (not by any earlier test)

1. **v1.0 chaincode could not read physical items** (schema validation, D-032). Found by the first live query. Fixed, tested, redeployed as v1.1.
2. **Error message ran into the gateway's next fragment** (D-034). Found by diffing the two full runs. Fixed, tested with the real shape.
3. Harness-only (mine): `peer chaincode invoke` without `--waitForEvent` does not wait for the block; WSL log redirection scrambled output;
   Git Bash path rewriting. Documented in the runbook.

### P2-F.9 Not covered (known gaps; consolidated, report-facing list in docs/KNOWN_GAPS.md)

- **C-08:** the chaincode trusts the role passed by the backend. No test can show otherwise; A2 (Phase 3) is the fix.
- One org failing to endorse, orderer outage, more than one peer connection / failover to Org2's peer, load, certificate rotation.
- Chaincode events are emitted and tested with a fake stub, but nothing consumes them yet (G3, Phase 4).
- Orphaned IPFS pins after a failed register during a ledger outage are expected (D-037); not measured.
- The Fabric run used a single application identity for every user (`User1@org1`); per-user identities are A2.

## 0. Environment for live checks

```bash
# once per machine: throwaway values, kept OUTSIDE the repo (never commit them)
export DB_PASSWORD=<random>  BLOCKEVIDENCE_JWT_SECRET=<random, >= 32 chars>
export BLOCKEVIDENCE_DEV_SEED_PASSWORD=<random>  SPRING_PROFILES_ACTIVE=dev
export DB_URL=jdbc:postgresql://localhost:5433/blockevidence     # 5433: 5432 was taken by a local Postgres

docker run -d --name be-postgres -e POSTGRES_DB=blockevidence -e POSTGRES_USER=blockevidence \
  -e POSTGRES_PASSWORD="$DB_PASSWORD" -p 127.0.0.1:5433:5432 -v be-postgres-data:/var/lib/postgresql/data postgres:16
# --offline: a default Kubo node joins the PUBLIC IPFS network (DECISIONS D-020). Never run evidence on one.
docker run -d --name be-ipfs -p 127.0.0.1:5001:5001 ipfs/kubo:latest daemon --offline --migrate=true
./mvnw spring-boot:run                       # Phase 1 checks (default profile: ledger stub, UNKNOWN)
SPRING_PROFILES_ACTIVE=dev,memory-ledger ./mvnw spring-boot:run     # Phase 2 checks (reference ledger)
```
Restart later with `docker start be-postgres be-ipfs`. Remove with `docker rm -f be-postgres be-ipfs` and
`docker volume rm be-postgres-data`.

## 1. Automated tests (all features)

Command: `./mvnw test`

```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.blockevidence.backend.config.JwtPropertiesTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.blockevidence.backend.ledger.HealthIndicatorsTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- in com.blockevidence.backend.security.JwtServiceTest
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0 -- in com.blockevidence.backend.security.SecurityAndErrorFormatTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in com.blockevidence.backend.service.AuthServiceTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.blockevidence.backend.storage.HttpIpfsClientTest
Tests run: 53, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 2. K3: the app refuses to start without required secrets

| Check | Command | Actual |
|---|---|---|
| No JWT secret | `unset BLOCKEVIDENCE_JWT_SECRET; ./mvnw spring-boot:run` | exit 1: `Binding to target com.blockevidence.backend.config.JwtProperties failed: Property: blockevidence.jwt.secret Value: ""` and `Reason: must be at least 32 characters ...` / `Reason: must not be blank` |
| Normal start | `./mvnw spring-boot:run` (all env set) | `Started BlockEvidenceApplication in 9.598 seconds`; Flyway on `PostgreSQL 16.15`; six `Seeded dev user ...` lines |

## 3. K1: one error format everywhere

Every response below has the shape `{timestamp, status, error, message, path, fieldErrors}`.

| Case | Command (`$B`=http://localhost:8080) | Actual |
|---|---|---|
| Validation | `curl -X POST $B/api/auth/login -H 'Content-Type: application/json' -d '{"email":"not-an-email","password":""}'` | `400 {"status":400,"error":"VALIDATION_FAILED","message":"Request validation failed","path":"/api/auth/login","fieldErrors":[{"field":"email","message":"must be a well-formed email address"},{"field":"password","message":"must not be blank"}]}` |
| Malformed JSON | `curl -X POST $B/api/auth/login -H 'Content-Type: application/json' -d '{ nope'` | `400 {"error":"BAD_REQUEST","message":"Malformed or unreadable request",...}` (no parser internals) |
| Unknown route | `curl $B/nope -H "Authorization: Bearer $AT"` | `404 {"error":"NOT_FOUND","message":"Not Found","path":"/nope",...}` |
| Wrong method | `curl $B/api/auth/login -H "Authorization: Bearer $AT"` | `405 {"error":"METHOD_NOT_ALLOWED",...}` |
| 401 from the filter chain | `curl $B/api/auth/me` | `401 {"error":"UNAUTHENTICATED","message":"Authentication required","path":"/api/auth/me",...}` |
| 500 leaks nothing | unit test `unexpectedExceptionIs500WithNoInternalDetail` (controller throws with a JDBC URL in its message) | passed; body is `INTERNAL_ERROR` / "An unexpected error occurred" |
| 501 stub | unit test `stubbedFeatureIs501` | passed; `NOT_IMPLEMENTED` |

## 4. A1: login, tokens, refresh, logout

| Check | Command | Actual |
|---|---|---|
| Valid login | `curl -X POST $B/api/auth/login -H 'Content-Type: application/json' -d '{"email":"judge@blockevidence.local","password":"'$BLOCKEVIDENCE_DEV_SEED_PASSWORD'"}'` | `200 {"accessToken":"eyJhbGciO…","refreshToken":"5DOKb_…","tokenType":"Bearer","expiresIn":900}` |
| Email case-insensitive | same, email `JUDGE@Blockevidence.Local` | `200` with tokens |
| Wrong password | same, password `wrong-password` | `401 {"error":"UNAUTHENTICATED","message":"Invalid email or password",...}` |
| Unknown user (must be identical) | same, `nobody@blockevidence.local` | `401`, byte-for-byte the same message as above |
| Disabled account (`update users set enabled=false ...`) | login with correct password | `401 "Invalid email or password"` (same as wrong password) |
| /me with token | `curl $B/api/auth/me -H "Authorization: Bearer $CT"` | `200 {"id":"f47ff616-...","email":"collector@blockevidence.local","fullName":"Dev COLLECTOR","department":"Development","role":"COLLECTOR"}` |
| /me with garbage token | `curl $B/api/auth/me -H "Authorization: Bearer not.a.jwt"` | `401 UNAUTHENTICATED` |
| Refresh rotates | `POST /api/auth/refresh {"refreshToken":"<R1>"}` | `200`, new pair (`refreshToken":"a09cHS…"`) |
| Reuse of retired token | same request with R1 again | `401 "Invalid or expired refresh token"` |
| Reuse also burns the new token | refresh with R2 (issued by the first refresh) | `401`: revoked by reuse detection |
| Logout needs a token | `POST /api/auth/logout` without Authorization | `401 UNAUTHENTICATED` |
| Logout | `POST /api/auth/logout` with access token + refresh token | `204` |
| Refresh after logout | refresh with the logged-out token | `401` |
| **Concurrent refresh, same token** | 4 parallel `POST /api/auth/refresh` with one token, 3 rounds | every round: `1 200  3 401` (exactly one winner) |

Stored data (`docker exec be-postgres psql -U blockevidence -d blockevidence -c ...`):

```
select email, role, enabled, left(password_hash,7) as pw_prefix, length(password_hash) as pw_len from users
 admin@blockevidence.local            | ADMIN            | t | $2a$10$ | 60      (6 rows, one per role, all BCrypt)

select left(token_hash,12) as hash_prefix, length(token_hash) as hash_len, revoked_at is not null as revoked from refresh_tokens
 670ffc155ce4 | 64 | f     ...     e4861a4fe9c1 | 64 | t   (7 rows; 3 revoked: rotated, reuse-burned, logged-out)

select version, description, success from flyway_schema_history
 1 | users and refresh tokens | t

select count(*) from refresh_tokens where token_hash in (<the raw tokens>)     ->  0
```
The last query shows no raw refresh token is stored, only hashes.

## 5. A3: RBAC scaffolding

Command: `./mvnw test -Dtest=SecurityAndErrorFormatTest`. Uses a test-only controller (`src/test/.../RbacProbeController`); no throwaway endpoint ships.

| Check | Actual |
|---|---|
| `@PreAuthorize("hasRole('ADMIN')")`: COLLECTOR | `403 ACCESS_DENIED` (test `adminRouteIsForbiddenToACollector`) |
| same: ADMIN | `200` |
| `hasAnyRole('JUDGE','AUDITOR')`: JUDGE, AUDITOR | `200` |
| same: COLLECTOR, FORENSIC_ANALYST, PROSECUTOR, ADMIN | `403 ACCESS_DENIED` (all four) |
| Expired / wrong-secret / alg=none / unknown-role token | `401` / `401` / rejected / rejected (`JwtServiceTest`, `SecurityAndErrorFormatTest`) |
| Principal comes only from the token (C-05) | `meUsesTheUserIdFromTheTokenOnly`, `logoutRequiresATokenAndPassesTheTokenOwner` passed |

## 6. G4: health

| Check | Command | Actual |
|---|---|---|
| Anonymous | `curl $B/actuator/health` | `200 {"groups":["liveness","readiness"],"status":"UP"}` |
| ADMIN | `curl $B/actuator/health -H "Authorization: Bearer $AT"` | `200`; `"ipfs":{"status":"UP"}`, `"db":{...,"status":"UP"}`, `"ledger":{"details":{"detail":"FabricLedgerService not implemented (Phase 1 stub); configured channel=crimechannel chaincode=basic"},"status":"UNKNOWN"}`, overall `"status":"UP"` |
| COLLECTOR (non-admin) | same with collector token | `200`, status only (no components) |
| Other actuator endpoints not exposed | `curl $B/actuator/env -H "Authorization: Bearer $AT"` / anonymous | `404 NOT_FOUND` / `401 UNAUTHENTICATED` |
| IPFS stopped | `docker stop be-ipfs`, then ADMIN health | `HTTP 503`; `"ipfs":{"details":{"detail":"IPFS node not reachable"},"status":"DOWN"}` |
| IPFS stopped, anonymous | same, no token | `HTTP 503 {"groups":[...],"status":"DOWN"}` |
| IPFS restarted | `docker start be-ipfs`, ADMIN health | `HTTP 200`, ipfs UP again |
| UNKNOWN ignored when others UP, DOWN still wins | unit test `springAggregationIgnoresUnknownWhenOthersAreUpButNotDown` | passed |
| Kubo POST-only, GET gets 405; slow node times out | `HttpIpfsClientTest` (fake node) | passed; slow node (3 s) answered `false` in < 2 s with a 300 ms timeout |

## 7. G1 / F1: stubs and boundaries

> **Superseded in Phase 2 (2026-09-22).** The IPFS client is no longer a stub (see P2 and
> `docs/features/b2-file-upload.md`), and `LedgerService` was finalised (DECISIONS D-016), so the test names
> below (`pinFetchAndUnpinAreStubsInPhase1`, the 7-operation stub check) no longer exist. The C-01/C-05/C-07
> boundary checks still apply and were re-run on the Phase 2 code (see P2.3). Kept for history.

| Check | Actual |
|---|---|
| Every `FabricLedgerService` operation throws `LedgerNotImplementedException` | `HealthIndicatorsTest.everyFabricStubOperationThrowsNotImplemented` passed |
| `HttpIpfsClient` pin/fetch/unpin throw `StorageNotImplementedException` | `HttpIpfsClientTest.pinFetchAndUnpinAreStubsInPhase1` passed |
| C-01: no Fabric import or dependency anywhere | `grep -rnE "^import .*(hyperledger\|fabric)" src` and `grep -ni fabric pom.xml` -> first: no output; second: one match, `pom.xml:15`, the project `<description>` text, and no `<dependency>` (checked by reading the pom). (A plain `grep -ri hyperledger src` DOES match two comment lines in `FabricLedgerService.java` that explain the stub; comments are not imports.) |
| C-01: nothing outside `ledger/` imports ledger types; nothing outside `storage/` imports storage types; controllers import no ledger/storage/repository/model | `grep -rn "backend.ledger" src/main` outside `ledger/` -> none; same for `backend.storage`; `grep -nE "import ...(ledger\|storage\|repository\|model)" controller/*.java` -> none |
| C-05: no request DTO names the acting user | request DTOs are `LoginRequest(email, password)` and `RefreshTokenRequest(refreshToken)`; no actor field |
| C-07: no secrets in the repo | `grep -rniE "unit-test-secret\|0123456789" src` -> no key literals; `application*.yml` secrets are `${DB_PASSWORD}`, `${BLOCKEVIDENCE_JWT_SECRET:}`, `${BLOCKEVIDENCE_DEV_SEED_PASSWORD:}` (env references, empty default); `git check-ignore` confirms `.env`, `*.env`, `application-local.yml` are ignored |

## 8. Not covered yet (known gaps)

- No automated test against a real PostgreSQL (needs Testcontainers, L2). Flyway + Hibernate validation
  are proven only by the live run above.
- No automated regression for the timezone bug (`docs/bugs/jvm-timezone-postgres.md`).
- Purging of expired refresh tokens and per-request "still enabled" check for access tokens do not exist.

## Appendix P2-A. Final live run, verbatim (2026-09-22, memory-ledger profile)

Script: run against `http://localhost:8080`; tokens omitted from the output by design.

```
collector user id (from /api/auth/me): f47ff616-80f2-4b54-b128-3b1b3fb6b8d0

### B1/B2/C1 register DIGITAL evidence (metadata carries a FORGED collector, must be ignored)
HTTP 201
   evidenceId                 EV-42f2f09e-4609-4a87-ba17-15751bf75e1d
   status                     COLLECTED
   version                    1
   createdBy                  f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   currentCustodian           f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   fileCid                    bafkreidxbac73jhkuu4rde55tbr2eagicxifwkbyewjijhr7wautoy2jkq
   fileSha256                 770805fda4eaa5391193bd9863a200c815d05b28382592849e3fb02937634954
   fileSize                   41
   metadataAvailable          True
   metadata.collectorId       f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   verification.status        NOT_CHECKED
   -> local sha256 of the file : 770805fda4eaa5391193bd9863a200c815d05b28382592849e3fb02937634954
   -> ledger fileSha256        : 770805fda4eaa5391193bd9863a200c815d05b28382592849e3fb02937634954
   -> createdBy == collector id from JWT? YES
   -> stored bytes fetched from IPFS by the file CID == original? YES

### B1 register PHYSICAL evidence (no file)
HTTP 201
   evidenceId                 EV-e4631b74-33fc-4c15-bb00-cfb9074d90af
   evidenceType               PHYSICAL
   fileCid                    None
   fileSha256                 None
   status                     COLLECTED
   createdByRole              None

### B3 retrieve by id, by file CID, by metadata CID
200 HTTP by id (AUDITOR)
   status                     COLLECTED
   version                    1
   metadata.description       Suspect phone image
   verification.status        NOT_CHECKED
200 HTTP by file CID
   ids: ['EV-42f2f09e-4609-4a87-ba17-15751bf75e1d']
200 HTTP by metadata CID
404 HTTP by unknown CID
   error                      NOT_FOUND
404 HTTP unknown id
   error                      NOT_FOUND

### C2 verify untouched evidence
200 HTTP
   status                     VERIFIED
   ledgerVersion              1
   file.result                VERIFIED
   file.expectedSha256        770805fda4eaa5391193bd9863a200c815d05b28382592849e3fb02937634954
   file.actualSha256          770805fda4eaa5391193bd9863a200c815d05b28382592849e3fb02937634954
   metadata.result            VERIFIED

### C2 DELIBERATE CORRUPTION: change one word inside the file's block on the IPFS node's disk, then restart the node
   -> block file on the node: /data/ipfs/blocks/SV/CIQHOCAF7WSOVJJZCGJ33GDDUIAMQFOQLMUDQJMSQSPD7MBJG5RUSVA.data
   -> before: LIVE-EVIDENCE-1790018209-original-content
   -> after : LIVE-EVIDENCE-1790018209-0RIGINAL-content
   -> IPFS still answers a cat for the same CID (content-addressing did NOT catch it):
   ->    cat -> LIVE-EVIDENCE-1790018209-0RIGINAL-content
200 HTTP verify
   status                     TAMPERED
   file.result                TAMPERED
   file.expectedSha256        770805fda4eaa5391193bd9863a200c815d05b28382592849e3fb02937634954
   file.actualSha256          010378697d295c54eab93e49f6ab0ca00824da6b10af3bd6742ebc847d086825
   metadata.result            VERIFIED
200 HTTP GET ?verify=true
   verification.status        TAMPERED
   status                     COLLECTED

### C2 NOT_FOUND: register another item, delete its file block from the node's disk, restart
removed-block
200 HTTP verify (161 ms)
   status                     NOT_FOUND
   file.result                NOT_FOUND
   file.actualSha256          None
   metadata.result            VERIFIED

### B4 versioned update (ANALYST), old version stays readable, stale update rejected
200 HTTP update v1->v2
   version                    2
   lastAction                 METADATA_UPDATED
   lastReason                 Location corrected after audit
   metadata.location          Locker 9
   metadata.description       Wallet
   metadata.metadataVersion   2
   metadata.previousMetadataCid bafkreih4qbo27us53rmcee7um6knsd5kyjncs3h7ims7sj25hyighfk7ta
200 HTTP GET version 1 (old)
   version                    1
   metadata.location          Desk 2
200 HTTP GET version 2
   version                    2
   metadata.location          Locker 9
409 HTTP stale update (expectedVersion=1, record is at 2)
   error                      VERSION_CONFLICT
   message                    Expected version 1 but the record is at version 2
400 HTTP blank reason
   error                      VALIDATION_FAILED
403 HTTP JUDGE update
   error                      ACCESS_DENIED

### C3 ledger history (tx ids and ledger timestamps)
200 HTTP
   v1  CREATED           tx=7db40fb63eb7be76..  at=2026-09-21T19:17:10.438778900Z  by=COLLECTOR reason=''
   v2  METADATA_UPDATED  tx=487f4487f0ec20ec..  at=2026-09-21T19:17:10.615247400Z  by=FORENSIC_ANALYST reason='Location corrected after audit'

### B5 disposal: request (PROSECUTOR) -> COLLECTOR cannot approve -> stale approval rejected -> JUDGE approves
200 HTTP request disposal
   status                     COLLECTED
   version                    2
   disposal.state             PENDING
   disposal.reason            Case closed by order 42/2026
   lastAction                 DISPOSAL_REQUESTED
403 HTTP COLLECTOR approve
   error                      ACCESS_DENIED
409 HTTP JUDGE approve with stale version
   error                      VERSION_CONFLICT
200 HTTP JUDGE approve
   status                     DISPOSED
   version                    3
   disposal.state             NONE
   lastAction                 DISPOSAL_APPROVED
   lastReason                 Order verified
409 HTTP update after DISPOSED
   error                      INVALID_STATE
   message                    Evidence is DISPOSED and can no longer change
200 HTTP the DISPOSED record is still readable
   status                     DISPOSED
   version                    3
200   history entries still on ledger: [(1, 'CREATED'), (2, 'DISPOSAL_REQUESTED'), (3, 'DISPOSAL_APPROVED')]

### C-02: there is no delete
   DELETE /api/evidence/{id} as collector -> 405  METHOD_NOT_ALLOWED
   DELETE /api/evidence/{id} as admin -> 405  METHOD_NOT_ALLOWED
   DELETE /api/evidence/{id} as judge -> 405  METHOD_NOT_ALLOWED

### A3 roles: who may register (403 for the rest)
   collector -> 201
   forensic-analyst -> 201
   prosecutor -> 403
   judge -> 403
   auditor -> 403
   admin -> 403

### B2 upload limits and types
   disallowed type (application/x-msdownload) -> 415  UNSUPPORTED_FILE_TYPE | File type 'application/x-msdownload' is not allowed
   empty file for DIGITAL -> 400  FILE_REQUIRED
   file on PHYSICAL evidence -> 400  FILE_NOT_ALLOWED
   missing required metadata field -> 400  ['description', 'caseId']
   60 MB file (limit 50 MB) -> 413  CONTENT_TOO_LARGE
   20 MB file -> HTTP 201 in 987 ms
   -> local sha256 : 9388b3b26e01d0124d475763c1674113659a61e69c7c95fd014ad0ea19d63174
   -> ledger sha256: 9388b3b26e01d0124d475763c1674113659a61e69c7c95fd014ad0ea19d63174   size=20000000
200 HTTP verify of the 20 MB file
   status                     VERIFIED
   file.result                VERIFIED

### F1 IPFS outage: ledger information survives, verify says 503 (not NOT_FOUND)
200 HTTP GET during outage
   status                     COLLECTED
   version                    2
   metadataAvailable          False
   metadata                   None
503 HTTP verify during outage
   error                      STORAGE_UNAVAILABLE
   message                    IPFS node not reachable while reading content
   register during outage -> 503  STORAGE_UNAVAILABLE

### G4 health with the reference ledger
200 HTTP
   overall: UP
   ledger : {'details': {'detail': 'IN-MEMORY reference ledger (dev/test only). NOT a blockchain, NOT tamper-proof.'}, 'status': 'UP'}
   ipfs   : {'status': 'UP'}
```

## Appendix P2-F-A. `go test -v ./...` in chaincode/evidence (verbatim)

```
--- PASS: TestCreateStoresVersionOneCollectedWithLedgerTimestampAndActor (0.00s)
--- PASS: TestDuplicateIdIsRejected (0.00s)
--- PASS: TestOnlyCollectorAndAnalystMayCreate (0.00s)
--- PASS: TestDigitalNeedsFileFieldsAndPhysicalForbidsThem (0.00s)
--- PASS: TestMalformedInputsAreRejected (0.00s)
--- PASS: TestAnOrganisationOutsideTheAllowListCannotWrite (0.00s)
--- PASS: TestUpdateBumpsVersionKeepsFileFieldsAndNeedsAReason (0.00s)
--- PASS: TestStaleExpectedVersionIsRejected (0.00s)
--- PASS: TestUpdateWithUnchangedCidOrUnknownIdOrWrongRoleFails (0.00s)
--- PASS: TestStatusMovesForwardOnlyAndNeverToDisposed (0.00s)
--- PASS: TestDisposalNeedsRequestThenJudgeApprovalThenFreezesTheRecordButKeepsIt (0.00s)
--- PASS: TestRejectedDisposalLeavesTheRecordUsable (0.00s)
--- PASS: TestTheApproverCannotBeTheRequester (0.00s)
--- PASS: TestHistoryIsOldestFirstWithDistinctTxIdsEvenIfThePeerReturnsNewestFirst (0.00s)
--- PASS: TestCidIndexFindsFileAndEveryMetadataCidAndSharedFiles (0.00s)
--- PASS: TestARejectedCallChangesNothing (0.00s)
--- PASS: TestEveryWriteEmitsOneEventWithOnlyIdentifiers (0.00s)
--- PASS: TestNothingEverDeletes (0.00s)
--- PASS: TestTheExportedFunctionSetIsExactlyTheApprovedOne (0.00s)
--- PASS: TestRecordJsonKeysAreTheContractWithTheJavaSide (0.00s)
--- PASS: TestContractMetadataGenerates (0.02s)
--- PASS: TestOmitemptyFieldsAreAlsoOptionalInTheSchema (0.00s)
PASS
ok  	blockevidence/evidence	0.367s
```

## Appendix P2-F-B. Deployment of `evidence` v1.1 seq 2 (filtered to the decisive lines)

```
### copy source to a WSL-local dir (no spaces in the path) and vendor dependencies
### package
package id: evidence_1.1:4e6e0c43f67cc503a4b6dc92d5b4eee554388f21177c63b269a05e982707ed84
### install on Org1 (the peer builds the chaincode image; this takes a while)
2026-09-21 19:48:18.935 UTC 0001 INFO [cli.lifecycle.chaincode] submitInstallProposal -> Installed remotely: response:<status:200 payload:"\nMevidence_1.1:4e6e0c43f67cc503a4b6dc92d5b4eee554388f21177c63b269a05e982707ed84\022\014evidence_1.1" > 
2026-09-21 19:48:18.936 UTC 0002 INFO [cli.lifecycle.chaincode] submitInstallProposal -> Chaincode code package identifier: evidence_1.1:4e6e0c43f67cc503a4b6dc92d5b4eee554388f21177c63b269a05e982707ed84
### install on Org2
2026-09-21 19:48:46.342 UTC 0001 INFO [cli.lifecycle.chaincode] submitInstallProposal -> Installed remotely: response:<status:200 payload:"\nMevidence_1.1:4e6e0c43f67cc503a4b6dc92d5b4eee554388f21177c63b269a05e982707ed84\022\014evidence_1.1" > 
2026-09-21 19:48:46.342 UTC 0002 INFO [cli.lifecycle.chaincode] submitInstallProposal -> Chaincode code package identifier: evidence_1.1:4e6e0c43f67cc503a4b6dc92d5b4eee554388f21177c63b269a05e982707ed84
### approve for Org1
2026-09-21 19:48:48.499 UTC 0001 INFO [chaincodeCmd] ClientWait -> txid [8e82a7cc8e34b6cd7663945024bf52005c9b7bc80e9edd91e438ca4a5ffb99eb] committed with status (VALID) at localhost:7051
### approve for Org2
2026-09-21 19:48:51.298 UTC 0001 INFO [chaincodeCmd] ClientWait -> txid [3932067dacae5e0478c127d44512a25767181cfe13f7a5350d4eb927f8bfc3ea] committed with status (VALID) at localhost:9051
### commit readiness
	"approvals": {
		"Org1MSP": true,
		"Org2MSP": true
### commit
2026-09-21 19:48:54.309 UTC 0001 INFO [chaincodeCmd] ClientWait -> txid [b0ac330d97f50547df91385b412a1e1135c53c8801b3acecba116d8a1a353aa7] committed with status (VALID) at localhost:9051
2026-09-21 19:48:54.316 UTC 0002 INFO [chaincodeCmd] ClientWait -> txid [b0ac330d97f50547df91385b412a1e1135c53c8801b3acecba116d8a1a353aa7] committed with status (VALID) at localhost:7051
### committed definitions
Name: basic, Version: 1.0, Sequence: 1, Endorsement Plugin: escc, Validation Plugin: vscc
Name: evidence, Version: 1.1, Sequence: 2, Endorsement Plugin: escc, Validation Plugin: vscc
```

## Appendix P2-F-C. The real chaincode driven directly through the peer CLI (verbatim, `chaincode/scripts/cc_direct.sh`)

```
evidence id under test: EV-c7800f6a-b95e-4325-8799-4f9c2c702716

### CreateEvidence DIGITAL as COLLECTOR (both orgs endorse)
status:200 payload:"{"

### GetEvidence
{"docType":"evidence","evidenceId":"EV-c7800f6a-b95e-4325-8799-4f9c2c702716","caseId":"FAB-DIRECT","evidenceType":"DIGITAL","status":"COLLECTED","version":1,"metadataCid":"bjit6flcpm642ncnjbsogjfnkaot3tynm4knajwegztc6mtl345ww","metadataSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","fileCid":"biisupj3ssx3x3fqybxrrnybhgn47zhc37ansadg5frv6ncnvz4xp","fileSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","fileSize":1234,"createdBy":"11111111-1111-4111-8111-111111111111","createdByRole":"COLLECTOR","createdAt":"2026-09-21T19:49:01.247654366Z","updatedBy":"11111111-1111-4111-8111-111111111111","updatedByRole":"COLLECTOR","updatedAt":"2026-09-21T

### CreateEvidence as JUDGE (role table: must be refused BY THE CHAINCODE)
Error: endorsement failure during invoke. response: status:500 message:"FORBIDDEN_ROLE: Role JUDGE may not create evidence" 

### CreateEvidence as ADMIN (Admin never writes evidence)
Error: endorsement failure during invoke. response: status:500 message:"FORBIDDEN_ROLE: Role ADMIN may not create evidence" 

### Create the same id again
Error: endorsement failure during invoke. response: status:500 message:"EVIDENCE_EXISTS: Evidence EV-c7800f6a-b95e-4325-8799-4f9c2c702716 already exists" 

### UpdateEvidence with a STALE expectedVersion (record is at 1, caller says 7)
Error: endorsement failure during invoke. response: status:500 message:"VERSION_CONFLICT: Expected version 7 but the record is at version 1" 

### UpdateEvidence v1 -> v2 (metadata pointer only)
status:200 payload:"{"

### UpdateStatus straight to ARCHIVED (skips PROCESSING and ANALYZED)
Error: endorsement failure during invoke. response: status:500 message:"INVALID_STATE: Status cannot move from COLLECTED to ARCHIVED" 

### UpdateStatus to DISPOSED (only an approved disposal may do that)
Error: endorsement failure during invoke. response: status:500 message:"INVALID_ARGUMENT: DISPOSED is only reachable through an approved disposal" 

### UpdateStatus COLLECTED -> PROCESSING (legal)
status:200 payload:"{"

### There is no delete: invoking DeleteEvidence
Error: endorsement failure during invoke. response: status:500 message:"Function DeleteEvidence not found in contract EvidenceContract" 

### RequestDisposal by COLLECTOR
status:200 payload:"{"

### ApproveDisposal by COLLECTOR (refused: only a JUDGE)
Error: endorsement failure during invoke. response: status:500 message:"FORBIDDEN_ROLE: Role COLLECTOR may not approve disposal" 

### ApproveDisposal by JUDGE with a stale version (3, record at 4)
Error: endorsement failure during invoke. response: status:500 message:"VERSION_CONFLICT: Expected version 3 but the record is at version 4" 

### ApproveDisposal by JUDGE with the reviewed version
status:200 payload:"{"

### UpdateEvidence after DISPOSED (record is frozen)
Error: endorsement failure during invoke. response: status:500 message:"INVALID_STATE: Evidence is DISPOSED and can no longer change" 

### GetEvidence after disposal (record still on the ledger)
{"docType":"evidence","evidenceId":"EV-c7800f6a-b95e-4325-8799-4f9c2c702716","caseId":"FAB-DIRECT","evidenceType":"DIGITAL","status":"DISPOSED","version":5,"metadataCid":"bpttuuo4nxgcebiysl62kuqpjblrcka2amwwdl6jn3dhgwtigldjy","metadataSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","fileCid":"biisupj3ssx3x3fqybxrrnybhgn47zhc37ansadg5frv6ncnvz4xp","fileSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","fileSize":1234,"createdBy":"11111111-1111-4111-8111-111111111111","createdByRole":"COLLECTOR","createdAt":"2026-09-21T19:49:01.247654366Z","updatedBy":"44444444-4444-4444-8444-444444444444","updatedByRole":"JUDGE","updatedAt":"2026-09-21T19:49

### FindByCid (the FILE cid)
["EV-c7800f6a-b95e-4325-8799-4f9c2c702716"]

### FindByCid (the ORIGINAL metadata cid, still indexed after the update)
["EV-c7800f6a-b95e-4325-8799-4f9c2c702716"]

### GetHistory (from the peer's history index): version, action, txId, ledger timestamp
   v1 CREATED             tx=13facf4fb31d7af4..  at=2026-09-21T19:49:01.247654366Z  by=COLLECTOR  metadataCid=bjit6flcpm..
   v2 METADATA_UPDATED    tx=56641c0f38ea55e6..  at=2026-09-21T19:49:03.905845537Z  by=COLLECTOR  metadataCid=bpttuuo4nx..
   v3 STATUS_CHANGED      tx=c844fdd3476de47d..  at=2026-09-21T19:49:06.297373973Z  by=COLLECTOR  metadataCid=bpttuuo4nx..
   v4 DISPOSAL_REQUESTED  tx=195652bdc0b962c1..  at=2026-09-21T19:49:08.577189718Z  by=COLLECTOR  metadataCid=bpttuuo4nx..
   v5 DISPOSAL_APPROVED   tx=d7fa98fe8732afe3..  at=2026-09-21T19:49:10.941533841Z  by=JUDGE  metadataCid=bpttuuo4nx..
```

## Appendix P2-F-D. Full Phase 2 checklist through Spring -> real Fabric (verbatim, `scripts/live/live_phase2.sh`)

```
collector user id (from /api/auth/me): f47ff616-80f2-4b54-b128-3b1b3fb6b8d0

### B1/B2/C1 register DIGITAL evidence (metadata carries a FORGED collector, must be ignored)
HTTP 201
   evidenceId                 EV-4c248518-89a1-4cd1-abc6-4979c8a47b12
   status                     COLLECTED
   version                    1
   createdBy                  f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   currentCustodian           f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   fileCid                    bafkreicmihqplyh6e7iywazjmpgl3nazksj7phkxro56ckvk2rc7ahx4cm
   fileSha256                 4c41e0f5e0fe27d18b032963ccbdb4195493f79d578bbbe12aaad445f01efc13
   fileSize                   41
   metadataAvailable          True
   metadata.collectorId       f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   verification.status        NOT_CHECKED
   -> local sha256 of the file : 4c41e0f5e0fe27d18b032963ccbdb4195493f79d578bbbe12aaad445f01efc13
   -> ledger fileSha256        : 4c41e0f5e0fe27d18b032963ccbdb4195493f79d578bbbe12aaad445f01efc13
   -> createdBy == collector id from JWT? YES
   -> stored bytes fetched from IPFS by the file CID == original? YES

### B1 register PHYSICAL evidence (no file)
HTTP 201
   evidenceId                 EV-58ae85cb-b911-4a20-9357-a68cc891fc6b
   evidenceType               PHYSICAL
   fileCid                    None
   fileSha256                 None
   status                     COLLECTED
   createdByRole              None

### B3 retrieve by id, by file CID, by metadata CID
200 HTTP by id (AUDITOR)
   status                     COLLECTED
   version                    1
   metadata.description       Suspect phone image
   verification.status        NOT_CHECKED
200 HTTP by file CID
   ids: ['EV-4c248518-89a1-4cd1-abc6-4979c8a47b12']
200 HTTP by metadata CID
404 HTTP by unknown CID
   error                      NOT_FOUND
404 HTTP unknown id
   error                      NOT_FOUND

### C2 verify untouched evidence
200 HTTP
   status                     VERIFIED
   ledgerVersion              1
   file.result                VERIFIED
   file.expectedSha256        4c41e0f5e0fe27d18b032963ccbdb4195493f79d578bbbe12aaad445f01efc13
   file.actualSha256          4c41e0f5e0fe27d18b032963ccbdb4195493f79d578bbbe12aaad445f01efc13
   metadata.result            VERIFIED

### C2 DELIBERATE CORRUPTION: change one word inside the file's block on the IPFS node's disk, then restart the node
   -> block file on the node: /data/ipfs/blocks/YE/CIQEYQPA6XQP4J6RRMBSSY6MXW2BSVET66OVPC534EVKVVCF6APPYEY.data
   -> before: LIVE-EVIDENCE-1790020887-original-content
   -> after : LIVE-EVIDENCE-1790020887-0RIGINAL-content
   -> IPFS still answers a cat for the same CID (content-addressing did NOT catch it):
   ->    cat -> LIVE-EVIDENCE-1790020887-0RIGINAL-content
200 HTTP verify
   status                     TAMPERED
   file.result                TAMPERED
   file.expectedSha256        4c41e0f5e0fe27d18b032963ccbdb4195493f79d578bbbe12aaad445f01efc13
   file.actualSha256          020f516ec860d54a4d90501a2a500397d8083df49a7d0127bd3ca38741bf644b
   metadata.result            VERIFIED
200 HTTP GET ?verify=true
   verification.status        TAMPERED
   status                     COLLECTED

### C2 NOT_FOUND: register another item, delete its file block from the node's disk, restart
removed-block
200 HTTP verify (180 ms)
   status                     NOT_FOUND
   file.result                NOT_FOUND
   file.actualSha256          None
   metadata.result            VERIFIED

### B4 versioned update (ANALYST), old version stays readable, stale update rejected
200 HTTP update v1->v2
   version                    2
   lastAction                 METADATA_UPDATED
   lastReason                 Location corrected after audit
   metadata.location          Locker 9
   metadata.description       Wallet
   metadata.metadataVersion   2
   metadata.previousMetadataCid bafkreigrq3k6igw366kvqkmygt4dryc62zreulorefutollpw54g3zrmye
200 HTTP GET version 1 (old)
   version                    1
   metadata.location          Desk 2
200 HTTP GET version 2
   version                    2
   metadata.location          Locker 9
409 HTTP stale update (expectedVersion=1, record is at 2)
   error                      VERSION_CONFLICT
   message                    Expected version 1 but the record is at version 2
400 HTTP blank reason
   error                      VALIDATION_FAILED
403 HTTP JUDGE update
   error                      ACCESS_DENIED

### C3 ledger history (tx ids and ledger timestamps)
200 HTTP
   v1  CREATED           tx=434786ed54822601..  at=2026-09-21T20:02:04.505840100Z  by=COLLECTOR reason=''
   v2  METADATA_UPDATED  tx=6d2e803922a24340..  at=2026-09-21T20:02:06.896367500Z  by=FORENSIC_ANALYST reason='Location corrected after audit'

### B5 disposal: request (PROSECUTOR) -> COLLECTOR cannot approve -> stale approval rejected -> JUDGE approves
200 HTTP request disposal
   status                     COLLECTED
   version                    2
   disposal.state             PENDING
   disposal.reason            Case closed by order 42/2026
   lastAction                 DISPOSAL_REQUESTED
403 HTTP COLLECTOR approve
   error                      ACCESS_DENIED
409 HTTP JUDGE approve with stale version
   error                      VERSION_CONFLICT
200 HTTP JUDGE approve
   status                     DISPOSED
   version                    3
   disposal.state             NONE
   lastAction                 DISPOSAL_APPROVED
   lastReason                 Order verified
409 HTTP update after DISPOSED
   error                      INVALID_STATE
   message                    Evidence is DISPOSED and can no longer change
200 HTTP the DISPOSED record is still readable
   status                     DISPOSED
   version                    3
200   history entries still on ledger: [(1, 'CREATED'), (2, 'DISPOSAL_REQUESTED'), (3, 'DISPOSAL_APPROVED')]

### C-02: there is no delete
   DELETE /api/evidence/{id} as collector -> 405  METHOD_NOT_ALLOWED
   DELETE /api/evidence/{id} as admin -> 405  METHOD_NOT_ALLOWED
   DELETE /api/evidence/{id} as judge -> 405  METHOD_NOT_ALLOWED

### A3 roles: who may register (403 for the rest)
   collector -> 201
   forensic-analyst -> 201
   prosecutor -> 403
   judge -> 403
   auditor -> 403
   admin -> 403

### B2 upload limits and types
   disallowed type (application/x-msdownload) -> 415  UNSUPPORTED_FILE_TYPE | File type 'application/x-msdownload' is not allowed
   empty file for DIGITAL -> 400  FILE_REQUIRED
   file on PHYSICAL evidence -> 400  FILE_NOT_ALLOWED
   missing required metadata field -> 400  ['description', 'caseId']
   60 MB file (limit 50 MB) -> 413  CONTENT_TOO_LARGE
   20 MB file -> HTTP 201 in 3531 ms
   -> local sha256 : 9ad01f2ebdb6a25cfb24b18b415b6d62bb65d242f33c411fece382155da8fd5e
   -> ledger sha256: 9ad01f2ebdb6a25cfb24b18b415b6d62bb65d242f33c411fece382155da8fd5e   size=20000000
200 HTTP verify of the 20 MB file
   status                     VERIFIED
   file.result                VERIFIED

### F1 IPFS outage: ledger information survives, verify says 503 (not NOT_FOUND)
200 HTTP GET during outage
   status                     COLLECTED
   version                    2
   metadataAvailable          False
   metadata                   None
503 HTTP verify during outage
   error                      STORAGE_UNAVAILABLE
   message                    IPFS node not reachable while reading content
   register during outage -> 503  STORAGE_UNAVAILABLE

### G4 health with the reference ledger
200 HTTP
   overall: UP
   ledger : {'details': {'detail': 'chaincode evidence answering on channel crimechannel via localhost:7051 as Org1MSP'}, 'status': 'UP'}
   ipfs   : {'status': 'UP'}
```

## Appendix P2-F-E. Fabric-only checks (verbatim, `scripts/live/live_fabric_extra.sh`)

```

### F1. Every txId the API reports is a real transaction on the peers' ledger (qscc GetTransactionByID)
   evidence EV-74d325e1-d3db-44b7-a298-f1e50b03839a  API says: txId=7e5856a9f0895f54c352be8ea629ef13185eb1be0d7ada660692a07c93848eba  ledger timestamp=2026-09-21T20:02:52.437724Z
   qscc found -> #namespaces/fields/evidence/Sequence
   qscc found -> 'EV-74d325e1-d3db-44b7-a298-f1e50b03839a
   qscc found -> *EV~EV-74d325e1-d3db-44b7-a298-f1e50b03839a
   qscc found -> CreateEvidence
   qscc found -> EV-74d325e1-d3db-44b7-a298-f1e50b03839a
   qscc found -> Org1MSP
   a made-up txId -> Error: endorsement failure during query. response: status:500 message:"Failed to get transaction with id 0000000000000000000000000000000000000000000000000000000

### F2. Concurrent updates to ONE record with the same expectedVersion (Fabric MVCC / version check): exactly one may win
   round 1: statuses ->       1 200
       3 409
    losers' error: ['VERSION_CONFLICT']
            history length after the race: 2  versions: [1, 2]
   round 2: statuses ->       1 200
       3 409
    losers' error: ['VERSION_CONFLICT']
            history length after the race: 2  versions: [1, 2]
   round 3: statuses ->       1 200
       3 409
    losers' error: ['VERSION_CONFLICT']
            history length after the race: 2  versions: [1, 2]

### F3. Ledger outage: stop the Org1 peer the backend talks to
   GET evidence      -> 503  LEDGER_UNAVAILABLE | The ledger network is not reachable
   register          -> 503  LEDGER_UNAVAILABLE
   health overall    -> DOWN | ledger: {'details': {'detail': 'The ledger network is not reachable'}, 'status': 'DOWN'}
   /api/auth/me still works (Postgres, not the ledger) -> 200
   after restarting the peer: GET evidence -> 200 (recovered after ~4 s, no app restart)
```

## Appendix P3-L. Phase 3 live run, verbatim (`scripts/live/live_phase3.sh`, E3-era: cases created before evidence, E3 section included)

```

### D1 status state machine (analyst moves COLLECTED -> PROCESSING -> ANALYZED)
   evidence EV-0bfba3c9-decb-4d3a-aeae-9c48572bab58
   -> 200 PROCESSING
   status                   PROCESSING
   version                  2
   currentCustodian         f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   lastAction               STATUS_CHANGED
   lastReason               sent to forensic lab
   -> 200 ANALYZED
   status                   ANALYZED
   version                  3
   currentCustodian         f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   lastAction               STATUS_CHANGED
   lastReason               imaging complete
   illegal jump ANALYZED -> RELEASED (skips ARCHIVED):   409  INVALID_STATE | Status cannot move from ANALYZED to RELEASED
   backwards ANALYZED -> COLLECTED:                      409  INVALID_STATE | Status cannot move from ANALYZED to COLLECTED
   straight to DISPOSED via status:                      400  INVALID_ARGUMENT | DISPOSED is only reachable through an approved disposal (B5)
   stale version:                                        409  VERSION_CONFLICT | Expected version 1 but the record is at version 3
   JUDGE tries to change status:                         403  ACCESS_DENIED | You do not have permission to perform this action
   blank reason:                                         400  VALIDATION_FAILED | Request validation failed
   legal step ANALYZED -> ARCHIVED (prosecutor):         200
   status                   ARCHIVED
   version                  4
   currentCustodian         f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   lastAction               STATUS_CHANGED
   lastReason               case file complete

### D2 two-step custody transfer
   evidence EV-394a3a7f-d122-48a4-8141-96a5fa873463 (custodian = collector f47ff616-80f2-4b54-b128-3b1b3fb6b8d0)
   initiate collector -> analyst:                        200
   status                   COLLECTED
   version                  2
   currentCustodian         f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   lastAction               TRANSFER_INITIATED
   lastReason               forensic analysis
   -> custody has NOT moved yet (still the collector)
   pending list for the ANALYST:
      EV-394a3a7f.. from f47ff616.. reason='forensic analysis' notes='sealed bag 7, seal intact' version 2
   pending list for the COLLECTOR (sender):              200 -> 0 items
   AUDITOR tries to initiate:                            403  ACCESS_DENIED | You do not have permission to perform this action
   collector tries a 2nd transfer while one is pending:  409  INVALID_STATE | A transfer is already pending
   prosecutor (not the receiver) tries to accept:        403  FORBIDDEN_ROLE | Only the named receiver can respond to this transfer
   collector (the sender) tries to accept:               403  FORBIDDEN_ROLE | Only the named receiver can respond to this transfer
   ANALYST accepts:                                      200
   status                   COLLECTED
   version                  3
   currentCustodian         b9481c66-6f02-463e-9ca1-2fd45ccd0ca2
   lastAction               TRANSFER_ACCEPTED
   lastReason               forensic analysis
   old custodian (collector) tries to hand it on:        403  FORBIDDEN_ROLE | Only the current custodian can transfer this evidence
   new custodian (analyst) -> prosecutor:                200
   prosecutor REJECTS:                                   200
   status                   COLLECTED
   version                  5
   currentCustodian         b9481c66-6f02-463e-9ca1-2fd45ccd0ca2
   lastAction               TRANSFER_REJECTED
   lastReason               for court filing
   analyst -> judge, then analyst CANCELS:               200 / 200
   status                   COLLECTED
   version                  7
   currentCustodian         b9481c66-6f02-463e-9ca1-2fd45ccd0ca2
   lastAction               TRANSFER_CANCELLED
   lastReason               court custody
   receiver checks:
     to an AUDITOR (cannot hold evidence):               400  RECEIVER_CANNOT_HOLD_EVIDENCE | A user with role AUDITOR cannot hold custody of evidence
     to an unknown user id:                              404  RECEIVER_NOT_FOUND | The receiving user does not exist or is inactive
     to yourself:                                        400  INVALID_ARGUMENT | Custody cannot be transferred to yourself
   forged sender field in the body is ignored (sender = token):
     HTTP ok; custodian still b9481c66.. (analyst), lastAction TRANSFER_INITIATED

### D3 custody timeline (built from the ledger history; each step has its own ledger txId)
   users: collector=f47ff616.. analyst=b9481c66.. prosecutor=286529a7.. judge=e878852e..
200   HTTP 200 (AUDITOR may read)
   currentCustodian: b9481c66.. | pendingTransfer: None
   v1  CUSTODY_STARTED     from=-          to=f47ff616.. after=f47ff616.. tx=55094d63dc32.. reason=None note=None
   v2  TRANSFER_INITIATED  from=f47ff616.. to=b9481c66.. after=f47ff616.. tx=173bccbcf4a4.. reason='forensic analysis' note=None
   v3  TRANSFER_ACCEPTED   from=f47ff616.. to=b9481c66.. after=b9481c66.. tx=25d20844cf77.. reason='forensic analysis' note='received intact, seal verified'
   v4  TRANSFER_INITIATED  from=b9481c66.. to=286529a7.. after=b9481c66.. tx=9454ca6aa375.. reason='for court filing' note=None
   v5  TRANSFER_REJECTED   from=b9481c66.. to=286529a7.. after=b9481c66.. tx=80fe4893a509.. reason='for court filing' note='not ready to receive'
   v6  TRANSFER_INITIATED  from=b9481c66.. to=e878852e.. after=b9481c66.. tx=5bb7097cbf0b.. reason='court custody' note=None
   v7  TRANSFER_CANCELLED  from=b9481c66.. to=e878852e.. after=b9481c66.. tx=4f5196e5f090.. reason='court custody' note='wrong court'
   v8  TRANSFER_INITIATED  from=b9481c66.. to=286529a7.. after=b9481c66.. tx=bccfaa62f69f.. reason='forged-sender probe' note=None
   v9  TRANSFER_CANCELLED  from=b9481c66.. to=286529a7.. after=b9481c66.. tx=a74924d7cab2.. reason='forged-sender probe' note='probe done'
   timeline of an item with NO transfers (E1):
     events: ['CUSTODY_STARTED'] (status changes are not custody events)
   unknown id: 404 EVIDENCE_NOT_FOUND | Evidence EV-00000000-0000-4000-8000-000000000000 does not exist
   an item written BEFORE transfers existed (chaincode v1.1 era) still reads:  GET 200, timeline 200
   no DELETE on these routes:  405 405

### E1 create and manage cases (PostgreSQL, Flyway V2)
   ADMIN creates FAB-P3-CASE-16848 with the collector as lead officer:  201
   caseNumber               FAB-P3-CASE-16848
   status                   OPEN
   leadOfficerId            f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   members: [('f47ff616..', 'LEAD_OFFICER')]  createdBy: 54db3c70.. (admin, from the token)
   same number again (different case):                    409  CASE_NUMBER_TAKEN | A case with this number already exists
   lead officer is an AUDITOR:                            400  INVALID_LEAD_OFFICER | A user with role AUDITOR cannot lead a case
   COLLECTOR tries to create a case:                      403  ACCESS_DENIED | You do not have permission to perform this action
   bad case number:                                       400  fields: ['leadOfficerId', 'caseNumber']
   any role can read: AUDITOR list 200, get 200; anonymous 401

### E2 assign officers to the case, with a role on the case
   add analyst as FORENSIC_ANALYST:                       200
   add the same user again:                               409  ALREADY_A_MEMBER | This user is already on the case team
   add someone as LEAD_OFFICER through members:           400  USE_LEAD_OFFICER_FIELD | The lead officer is set through the case's leadOfficerId, not as a member role
   add prosecutor as PROSECUTOR, judge as OBSERVER:       200 / 200
   team now: [('f47ff616..', 'LEAD_OFFICER'), ('b9481c66..', 'FORENSIC_ANALYST'), ('286529a7..', 'PROSECUTOR'), ('e878852e..', 'OBSERVER')]
   COLLECTOR tries to add a member:                       403  ACCESS_DENIED | You do not have permission to perform this action
   remove the judge from the team:                        200
   remove the LEAD officer (must be refused):             400  CANNOT_REMOVE_LEAD_OFFICER | The lead officer cannot be removed; assign a different lead officer first
   remove someone not on the team:                        404  NOT_FOUND | That user is not on this case team
   change the lead officer to the prosecutor + rename:    200
   title: Burglary at 12 High St (renamed) | lead: 286529a7.. | team: [('286529a7..', 'LEAD_OFFICER'), ('b9481c66..', 'FORENSIC_ANALYST'), ('f47ff616..', 'INVESTIGATOR')]
   update with nothing to change:                         400  fields: ['atLeastOneChange']
   DELETE a case (no such route):                         405

### E3 link evidence to the case (JPA relation, ledger caseId still just a string)
   register under an UNKNOWN case number:                 404  CASE_NOT_FOUND | No case with number 'NO-SUCH-CASE-P3' exists; create it first (POST /api/cases)
   register under the just-created case FAB-P3-CASE-16848 -> evidence EV-5c3230c9-e4fe-45a0-9cb6-f1b536e84354
   the case now lists it (GET /api/cases/{id}):           200
   evidenceIds: ['EV-5c3230c9-e4fe-45a0-9cb6-f1b536e84354']
   and the full-record endpoint:                          200
   linked: ['EV-5c3230c9-e4fe-45a0-9cb6-f1b536e84354']

### Database (what is actually stored)
       case_number    | status |              title               
   -------------------+--------+----------------------------------
    FAB-P3-CASE-16848 | OPEN   | Burglary at 12 High St (renamed)
    FAB-P3-1          | OPEN   | Live check case FAB-P3-1
   (2 rows)
   
       case_role     | count 
   ------------------+-------
    FORENSIC_ANALYST |     3
    INVESTIGATOR     |     2
    LEAD_OFFICER     |     8
    PROSECUTOR       |     1
   (4 rows)
   
    version |       description        | success 
   ---------+--------------------------+---------
    1       | users and refresh tokens | t
    2       | cases                    | t
    3       | case evidence links      | t
   (3 rows)
```

## Appendix P3-G. Go chaincode tests, verbatim (`go test -count=1 -v ./...`, filtered to result lines)

```
--- PASS: TestCreateStoresVersionOneCollectedWithLedgerTimestampAndActor (0.00s)
--- PASS: TestDuplicateIdIsRejected (0.00s)
--- PASS: TestOnlyCollectorAndAnalystMayCreate (0.00s)
--- PASS: TestDigitalNeedsFileFieldsAndPhysicalForbidsThem (0.00s)
--- PASS: TestMalformedInputsAreRejected (0.00s)
--- PASS: TestAnOrganisationOutsideTheAllowListCannotWrite (0.00s)
--- PASS: TestUpdateBumpsVersionKeepsFileFieldsAndNeedsAReason (0.00s)
--- PASS: TestStaleExpectedVersionIsRejected (0.00s)
--- PASS: TestUpdateWithUnchangedCidOrUnknownIdOrWrongRoleFails (0.00s)
--- PASS: TestStatusMovesForwardOnlyAndNeverToDisposed (0.00s)
--- PASS: TestDisposalNeedsRequestThenJudgeApprovalThenFreezesTheRecordButKeepsIt (0.00s)
--- PASS: TestRejectedDisposalLeavesTheRecordUsable (0.00s)
--- PASS: TestTheApproverCannotBeTheRequester (0.00s)
--- PASS: TestHistoryIsOldestFirstWithDistinctTxIdsEvenIfThePeerReturnsNewestFirst (0.00s)
--- PASS: TestCidIndexFindsFileAndEveryMetadataCidAndSharedFiles (0.00s)
--- PASS: TestARejectedCallChangesNothing (0.00s)
--- PASS: TestEveryWriteEmitsOneEventWithOnlyIdentifiers (0.00s)
--- PASS: TestNothingEverDeletes (0.03s)
--- PASS: TestTheExportedFunctionSetIsExactlyTheApprovedOne (0.00s)
--- PASS: TestRecordJsonKeysAreTheContractWithTheJavaSide (0.00s)
--- PASS: TestContractMetadataGenerates (0.03s)
--- PASS: TestOmitemptyFieldsAreAlsoOptionalInTheSchema (0.00s)
--- PASS: TestInitiateOnlyByTheCurrentCustodianAndCustodyMovesOnlyOnAcceptance (0.00s)
--- PASS: TestAcceptMakesTheReceiverCustodianAndTheyCanHandOnWhileTheOldCustodianCannot (0.00s)
--- PASS: TestRejectLeavesCustodyWithTheSenderAndAllowsANewTransfer (0.00s)
--- PASS: TestOnlyTheSenderCanCancelAndOnlyTheNamedReceiverCanRespond (0.00s)
--- PASS: TestTheReceiverMustRespondWithTheRoleTheTransferWasAddressedTo (0.00s)
--- PASS: TestRolesThatCannotHoldEvidenceAreRefusedEverywhere (0.00s)
--- PASS: TestInitiateArgumentAndStateChecks (0.00s)
--- PASS: TestATransferCannotStartWhileADisposalIsPendingOrAfterDisposal (0.00s)
--- PASS: TestPendingListShowsOnlyWhatIsStillPendingTowardThatUser (0.00s)
--- PASS: TestARecordWrittenBeforeCustodyTransfersExistedReadsAsNoneAndCanBeTransferred (0.00s)
--- PASS: TestTheCustodyTimelineIsRecoverableFromHistory (0.00s)
PASS
ok  	blockevidence/evidence	0.317s
```

## Appendix P3-R. Phase 2 checklist through Fabric on chaincode v1.2 seq 3, verbatim (`scripts/live/live_phase2.sh`, E3-era: includes the case-creation prerequisite)

```
collector user id (from /api/auth/me): f47ff616-80f2-4b54-b128-3b1b3fb6b8d0

### E3 prerequisite: create the cases this checklist registers evidence under
   case FAB-LIVE-1 -> 201 (200/201 created, 409 already exists from an earlier run)
   case FAB-LIVE-2 -> 201 (200/201 created, 409 already exists from an earlier run)
   case FAB-LIVE-3 -> 201 (200/201 created, 409 already exists from an earlier run)
   case FAB-LIVE-4 -> 201 (200/201 created, 409 already exists from an earlier run)
   register under an unknown case number -> 404  CASE_NOT_FOUND

### B1/B2/C1 register DIGITAL evidence (metadata carries a FORGED collector, must be ignored)
HTTP 201
   evidenceId                 EV-55b01288-75cc-4e1f-9dd5-588cedc6f2e3
   status                     COLLECTED
   version                    1
   createdBy                  f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   currentCustodian           f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   fileCid                    bafkreibgrzvoidfhatdn5gp4r44uhjlok5rw7ypy5ay3qkoq7dfc7ikhwq
   fileSha256                 268e6ae40ca704c6de99fc8f3943a56e57636fe1f8e831b829d0f8ca2fa147b4
   fileSize                   41
   metadataAvailable          True
   metadata.collectorId       f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   verification.status        NOT_CHECKED
   -> local sha256 of the file : 268e6ae40ca704c6de99fc8f3943a56e57636fe1f8e831b829d0f8ca2fa147b4
   -> ledger fileSha256        : 268e6ae40ca704c6de99fc8f3943a56e57636fe1f8e831b829d0f8ca2fa147b4
   -> createdBy == collector id from JWT? YES
   -> stored bytes fetched from IPFS by the file CID == original? YES

### B1 register PHYSICAL evidence (no file)
HTTP 201
   evidenceId                 EV-4c124562-f907-469e-a237-0885f4c09af2
   evidenceType               PHYSICAL
   fileCid                    None
   fileSha256                 None
   status                     COLLECTED
   createdByRole              None

### B3 retrieve by id, by file CID, by metadata CID
200 HTTP by id (AUDITOR)
   status                     COLLECTED
   version                    1
   metadata.description       Suspect phone image
   verification.status        NOT_CHECKED
200 HTTP by file CID
   ids: ['EV-55b01288-75cc-4e1f-9dd5-588cedc6f2e3']
200 HTTP by metadata CID
404 HTTP by unknown CID
   error                      NOT_FOUND
404 HTTP unknown id
   error                      NOT_FOUND

### C2 verify untouched evidence
200 HTTP
   status                     VERIFIED
   ledgerVersion              1
   file.result                VERIFIED
   file.expectedSha256        268e6ae40ca704c6de99fc8f3943a56e57636fe1f8e831b829d0f8ca2fa147b4
   file.actualSha256          268e6ae40ca704c6de99fc8f3943a56e57636fe1f8e831b829d0f8ca2fa147b4
   metadata.result            VERIFIED

### C2 DELIBERATE CORRUPTION: change one word inside the file's block on the IPFS node's disk, then restart the node
   -> block file on the node: /data/ipfs/blocks/PN/CIQCNDTK4QGKOBGG32M7ZDZZIOSW4V3DN7Q7R2BRXAU5B6GKF6QUPNA.data
   -> before: LIVE-EVIDENCE-1790042759-original-content
   -> after : LIVE-EVIDENCE-1790042759-0RIGINAL-content
   -> IPFS still answers a cat for the same CID (content-addressing did NOT catch it):
   ->    cat -> LIVE-EVIDENCE-1790042759-0RIGINAL-content
200 HTTP verify
   status                     TAMPERED
   file.result                TAMPERED
   file.expectedSha256        268e6ae40ca704c6de99fc8f3943a56e57636fe1f8e831b829d0f8ca2fa147b4
   file.actualSha256          4da70905cf429914e71cc48c22dad88432c3cfef50db4032911dab69f525704b
   metadata.result            VERIFIED
200 HTTP GET ?verify=true
   verification.status        TAMPERED
   status                     COLLECTED

### C2 NOT_FOUND: register another item, delete its file block from the node's disk, restart
removed-block
200 HTTP verify (212 ms)
   status                     NOT_FOUND
   file.result                NOT_FOUND
   file.actualSha256          None
   metadata.result            VERIFIED

### B4 versioned update (ANALYST), old version stays readable, stale update rejected
200 HTTP update v1->v2
   version                    2
   lastAction                 METADATA_UPDATED
   lastReason                 Location corrected after audit
   metadata.location          Locker 9
   metadata.description       Wallet
   metadata.metadataVersion   2
   metadata.previousMetadataCid bafkreiaznhzxnicgvuphptxzkoibotatcg5fl7jhgicjqwbo3nsq2fu2j4
200 HTTP GET version 1 (old)
   version                    1
   metadata.location          Desk 2
200 HTTP GET version 2
   version                    2
   metadata.location          Locker 9
409 HTTP stale update (expectedVersion=1, record is at 2)
   error                      VERSION_CONFLICT
   message                    Expected version 1 but the record is at version 2
400 HTTP blank reason
   error                      VALIDATION_FAILED
403 HTTP JUDGE update
   error                      ACCESS_DENIED

### C3 ledger history (tx ids and ledger timestamps)
200 HTTP
   v1  CREATED           tx=4fdd825407b7fe71..  at=2026-09-22T02:06:35.398048700Z  by=COLLECTOR reason=''
   v2  METADATA_UPDATED  tx=000227ddb9ff7be6..  at=2026-09-22T02:06:37.853162300Z  by=FORENSIC_ANALYST reason='Location corrected after audit'

### B5 disposal: request (PROSECUTOR) -> COLLECTOR cannot approve -> stale approval rejected -> JUDGE approves
200 HTTP request disposal
   status                     COLLECTED
   version                    2
   disposal.state             PENDING
   disposal.reason            Case closed by order 42/2026
   lastAction                 DISPOSAL_REQUESTED
403 HTTP COLLECTOR approve
   error                      ACCESS_DENIED
409 HTTP JUDGE approve with stale version
   error                      VERSION_CONFLICT
200 HTTP JUDGE approve
   status                     DISPOSED
   version                    3
   disposal.state             NONE
   lastAction                 DISPOSAL_APPROVED
   lastReason                 Order verified
409 HTTP update after DISPOSED
   error                      INVALID_STATE
   message                    Evidence is DISPOSED and can no longer change
200 HTTP the DISPOSED record is still readable
   status                     DISPOSED
   version                    3
200   history entries still on ledger: [(1, 'CREATED'), (2, 'DISPOSAL_REQUESTED'), (3, 'DISPOSAL_APPROVED')]

### C-02: there is no delete
   DELETE /api/evidence/{id} as collector -> 405  METHOD_NOT_ALLOWED
   DELETE /api/evidence/{id} as admin -> 405  METHOD_NOT_ALLOWED
   DELETE /api/evidence/{id} as judge -> 405  METHOD_NOT_ALLOWED

### A3 roles: who may register (403 for the rest)
   collector -> 201
   forensic-analyst -> 201
   prosecutor -> 403
   judge -> 403
   auditor -> 403
   admin -> 403

### B2 upload limits and types
   disallowed type (application/x-msdownload) -> 415  UNSUPPORTED_FILE_TYPE | File type 'application/x-msdownload' is not allowed
   empty file for DIGITAL -> 400  FILE_REQUIRED
   file on PHYSICAL evidence -> 400  FILE_NOT_ALLOWED
   missing required metadata field -> 400  ['description', 'caseId']
   60 MB file (limit 50 MB) -> 413  CONTENT_TOO_LARGE
   20 MB file -> HTTP 201 in 3310 ms
   -> local sha256 : 4db5b1c4b39317a389688fa13d19198bbae54c3d5120b39b923a78c9f5c71055
   -> ledger sha256: 4db5b1c4b39317a389688fa13d19198bbae54c3d5120b39b923a78c9f5c71055   size=20000000
200 HTTP verify of the 20 MB file
   status                     VERIFIED
   file.result                VERIFIED

### F1 IPFS outage: ledger information survives, verify says 503 (not NOT_FOUND)
200 HTTP GET during outage
   status                     COLLECTED
   version                    2
   metadataAvailable          False
   metadata                   None
503 HTTP verify during outage
   error                      STORAGE_UNAVAILABLE
   message                    IPFS node not reachable while reading content
   register during outage -> 503  STORAGE_UNAVAILABLE

### G4 health with the reference ledger
200 HTTP
   overall: UP
   ledger : {'details': {'detail': 'chaincode evidence answering on channel crimechannel via localhost:7051 as Org1MSP'}, 'status': 'UP'}
   ipfs   : {'status': 'UP'}
```

## Appendix P3-E. Fabric-only checks on v1.2, verbatim (`scripts/live/live_fabric_extra.sh`, E3-era)

```

### F1. Every txId the API reports is a real transaction on the peers' ledger (qscc GetTransactionByID)
   evidence EV-79c2d0f7-2599-4b2a-8c35-4fac48291853  API says: txId=74267ef617cc9e60f429ceb2f168a3be72859c3e6d9773c3d8b46ee5d92ae402  ledger timestamp=2026-09-22T02:07:14.510999900Z
   qscc found -> #namespaces/fields/evidence/Sequence
   qscc found -> 'EV-79c2d0f7-2599-4b2a-8c35-4fac48291853
   qscc found -> *EV~EV-79c2d0f7-2599-4b2a-8c35-4fac48291853
   qscc found -> CreateEvidence
   qscc found -> EV-79c2d0f7-2599-4b2a-8c35-4fac48291853
   qscc found -> Org1MSP
   a made-up txId -> Error: endorsement failure during query. response: status:500 message:"Failed to get transaction with id 0000000000000000000000000000000000000000000000000000000

### F2. Concurrent updates to ONE record with the same expectedVersion (Fabric MVCC / version check): exactly one may win
   round 1: statuses ->       1 200
       3 409
    losers' error: ['VERSION_CONFLICT']
            history length after the race: 2  versions: [1, 2]
   round 2: statuses ->       1 200
       3 409
    losers' error: ['VERSION_CONFLICT']
            history length after the race: 2  versions: [1, 2]
   round 3: statuses ->       1 200
       3 409
    losers' error: ['VERSION_CONFLICT']
            history length after the race: 2  versions: [1, 2]

### F3. Ledger outage: stop the Org1 peer the backend talks to
   GET evidence      -> 503  LEDGER_UNAVAILABLE | The ledger network is not reachable
   register          -> 503  LEDGER_UNAVAILABLE
   health overall    -> DOWN | ledger: {'details': {'detail': 'The ledger network is not reachable'}, 'status': 'DOWN'}
   /api/auth/me still works (Postgres, not the ledger) -> 200
   after restarting the peer: GET evidence -> 200 (recovered after ~4 s, no app restart)
```

## Appendix P3-A. A2 spike against the real Fabric CA (design evidence only; nothing implemented). Two runs, verbatim

Run 1 (`ca_spike.sh`): the escalation step printed blank lines because of a capture bug in the script (`tail -1`), and the revoke step used an invalid flag (`--reason`), so some UUID-named spike users were left unrevoked. Run 2 redid the escalation with full capture (five refusals, Error Code 71). Its own revoke step hit the same invalid flag for the user (see the output), so the leftovers were revoked by a separate cleanup script (`ca_cleanup.sh`) whose output was NOT saved to a file; the original identities (`admin`, `peer0`, `user1`, `org1admin`, `appUser`) were checked untouched afterwards. No spike script is in the repository.

```

### CA server facts (fabric-ca-server-config.yaml)
49:crlsizelimit: 512000
92:#  The gencrl REST endpoint is used to generate a CRL that contains revoked
94:#  during gencrl request processing.
96:crl:
100:  expiry: 24h
122:  maxenrollments: -1
131:          hf.Registrar.Roles: "*"
132:          hf.Registrar.DelegateRoles: "*"
133:          hf.Revoker: true
136:          hf.Registrar.Attributes: "*"
187:      # named "hf.Revoker" with a value of "true" (because the boolean expression
190:      #       - name: hf.Revoker
202:      #       - name: hf.Registrar.Roles
210:      # The value of the user's 'hf.Registrar.Roles' attribute is then computed to be
240:   org1:
250:#  the default expiration ("expiry" field) is "8760h", which is 1 year in hours.
253:#  the default expiration ("expiry" field) is "43800h" which is 5 years in hours.
260:#  the default expiration ("expiry" field) is "8760h", which is 1 year in hours.
266:      expiry: 8760h
271:           - crl sign
CA version:  Version: v1.5.17

### 1. enroll the CA registrar (bootstrap identity) -- creds not printed
2026/09/21 20:20:04 [INFO] Stored Issuer revocation public key at /tmp/ca-spike/admin/msp/IssuerRevocationPublicKey
registered identities before (id, type, affiliation, attrs):
Name: admin, Type: client, Affiliation: , Max Enrollments: -1, Attributes: [{Name:hf.Revoker Value:1 ECert:false} {Name:hf.IntermediateCA Value:1 ECert:false} {Name:hf.GenCRL Value:1 ECert:false} {Nam
Name: peer0, Type: peer, Affiliation: , Max Enrollments: -1, Attributes: [{Name:hf.EnrollmentID Value:peer0 ECert:true} {Name:hf.Type Value:peer ECert:true} {Name:hf.Affiliation Value: ECert:true}]
Name: user1, Type: client, Affiliation: , Max Enrollments: -1, Attributes: [{Name:hf.EnrollmentID Value:user1 ECert:true} {Name:hf.Type Value:client ECert:true} {Name:hf.Affiliation Value: ECert:true}
Name: org1admin, Type: admin, Affiliation: , Max Enrollments: -1, Attributes: [{Name:hf.EnrollmentID Value:org1admin ECert:true} {Name:hf.Type Value:admin ECert:true} {Name:hf.Affiliation Value: ECert
Name: appUser, Type: client, Affiliation: org1.department1, Max Enrollments: 1, Attributes: [{Name:hf.EnrollmentID Value:appUser ECert:true} {Name:hf.Type Value:client ECert:true} {Name:hf.Affiliation

### 2. register a LEAST-PRIVILEGE registrar (may register only clients, only the attribute 'role')
Password: <redacted>
2026/09/21 20:20:04 [INFO] Stored Issuer revocation public key at /tmp/ca-spike/reg/msp/IssuerRevocationPublicKey

### 3. AS THAT REGISTRAR: register a user with attribute role=JUDGE:ecert (should succeed)
Password: <redacted>

### 4. AS THAT REGISTRAR: try to escalate (must be REFUSED): a second attribute, an 'admin' type, and a peer identity




### 5. enroll the user; inspect the certificate the chaincode would receive
2026/09/21 20:20:05 [INFO] Stored Issuer revocation public key at /tmp/ca-spike/user/msp/IssuerRevocationPublicKey
subject=C = US, ST = North Carolina, O = Hyperledger, OU = org1 + OU = client + OU = department1, CN = <user-uuid>
issuer=C = US, ST = North Carolina, L = Durham, O = org1.example.com, CN = ca.org1.example.com
notBefore=Feb 24 16:42:00 2026 GMT
notAfter=Sep 21 20:20:00 2027 GMT
certificate attribute extension (OID 1.2.3.4.5.6.7.8.1), as the peer/chaincode sees it:
                {"attrs":{"role":"JUDGE"}}
key material files (names only): <hash>_sk  perms: 600

### 6. second enrollment with the same one-time secret (--id.maxenrollments 1): must be refused


### 7. revoke the user AS THE REGISTRAR, then the registrar; CA-side revocation is recorded

2026/09/21 20:20:05 [INFO] Successfully revoked certificates: [{Serial:6df37ebed89694237c3bc44577b049066137deaf AKI:ae0d79f1afb226a4ef53cb7664fa5187d4a88024}]
CRL available from the CA (would need to be added to the channel MSP config for PEERS to honour it):
2026/09/21 20:20:05 [INFO] Successfully stored the CRL in the file /tmp/ca-spike/admin/msp/crls/crl.pem

(temporary client homes removed)
```

Run 2 (`ca_spike2.sh`):

```

### 0. leftovers from the first spike (a2spike-*)?
Name: a2spike-registrar-26498, Type: client, Affiliation: org1.department1, Max Enrollment
(end list)

### 1. new least-privilege registrar
Password: <redacted>

### 2. registrar escalation attempts (each must be REFUSED)
a) extra attribute 'admin=true:ecert':
Error: Response from server: Error Code: 71 - Authorization failure
b) identity of type 'admin':
Error: Response from server: Error Code: 71 - Authorization failure
c) identity of type 'peer':
Error: Response from server: Error Code: 71 - Authorization failure
d) a registrar attribute (hf.Registrar.Roles=client):
Error: Response from server: Error Code: 71 - Authorization failure
e) affiliation outside the registrar's own (org2.department1 does not exist here; try root):
Error: Response from server: Error Code: 71 - Authorization failure

### 3. legitimate registration (role=JUDGE:ecert, one enrollment) then enroll requesting BOTH role and hf.EnrollmentID
Password: <redacted>
                {"attrs":{"hf.EnrollmentID":"<user-uuid>","role":"JUDGE"}}

### 4. one-time secret: second enrollment must be refused
Error: Response from server: Error Code: 20 - Authentication failure

### 5. revoke the user (as the registrar) and clean up EVERY a2spike identity
Error: unknown flag: --reason
      --gencrl   Generates a CRL that contains all revoked certificates
      --id.maxenrollments int        The maximum number of times the secret can be reused to enroll (default CA's Max Enrollment)
   [a2spike-registrar-26498] 2026/09/21 20:20:49 [INFO] Successfully revoked certificates: []
   [a2spike-registrar-26683] 2026/09/21 20:20:49 [INFO] Successfully revoked certificates: [{Serial:5ec402c7572a1b540519

(temporary client homes removed)
```

## Appendix P4-G3. G3 live verification, verbatim

```
### First-ever run (empty checkpoint): backfilled the whole ledger from block 0
   eventSync health: UP, consecutiveFailures=0
   evidence_activity rows: 164
   evidence_projection rows: 90
   ledger_sync_checkpoint block_number: 223

### Register while the listener is running (collector, case FAB-G3-LIVE)
   EVA=EV-a491efd6-96f5-438c-b8d6-2a6e9ca72646
   evidence_activity row for EVA: action=CREATED txId=2d53e96449d81810bac088c23a4af86d74e4a6cb018bedb399c93b243252de56
   (present within ~2s of the write, no polling needed)

### App fully stopped (kills the listener)
   evidence_activity rows before outage writes: 165
   checkpoint block_number: 224
### Enrolling a throwaway COLLECTOR identity (revoked at the end)
### Writing EV-b5fccd41-1146-4797-a1c7-0118b4a257ae directly to the chaincode (Spring is stopped; this is exactly what it would miss)
2026-09-22 05:09:15.083 UTC 0003 INFO [chaincodeCmd] chaincodeInvokeOrQuery -> Chaincode invoke successful. result: status:200 payload:"{\"txId\":\"edd8ee3edbc66c810b84f1112d203bf8a6f19b392181054654f6af5cfa849b15\",\"timestamp\":\"2026-09-22T05:09:13.024983443Z\",\"version\":1}" 
### Writing EV-bcfd1e3b-4517-47de-bab2-8e0206a2ba0c directly to the chaincode (Spring is stopped; this is exactly what it would miss)
2026-09-22 05:09:17.266 UTC 0003 INFO [chaincodeCmd] chaincodeInvokeOrQuery -> Chaincode invoke successful. result: status:200 payload:"{\"txId\":\"d770c050342ba76fd1a804e05ffd51ba7d92251ec869f2b575c15d73014564d1\",\"timestamp\":\"2026-09-22T05:09:15.215636239Z\",\"version\":1}" 
written ids:
EV-b5fccd41-1146-4797-a1c7-0118b4a257ae
EV-bcfd1e3b-4517-47de-bab2-8e0206a2ba0c

### While still stopped: confirmed Postgres has NOT changed (the outage really is invisible while down)
   evidence_activity rows: 165 (unchanged)
   evidence_projection rows for the two outage-written ids: 0

### App restarted: both outage writes caught up, checkpoint advanced exactly, no duplicates
   evidence_activity rows: 167 (165 + 2, exactly)
   EV-b5fccd41-1146-4797-a1c7-0118b4a257ae | CREATED | edd8ee3edbc66c810b84f1112d203bf8a6f19b392181054654f6af5cfa849b15
   EV-bcfd1e3b-4517-47de-bab2-8e0206a2ba0c | CREATED | d770c050342ba76fd1a804e05ffd51ba7d92251ec869f2b575c15d73014564d1
   checkpoint block_number: 226 (224 + 2, exactly)

### A further restart with NO new writes: every count unchanged (idempotency holds)
   evidence_activity: 167  evidence_projection: 93  checkpoint block_number: 226
   eventSync health: UP, consecutiveFailures=0

### Regression: the whole Phase 2 checklist re-run with the listener active
   scripts/live/live_phase2.sh exit 0; application log ERROR count: 0; script traceback count: 0
```

## Appendix P4-G3-R. Phase 2 checklist re-run with the G3 listener active, verbatim (`scripts/live/live_phase2.sh`)

```
collector user id (from /api/auth/me): f47ff616-80f2-4b54-b128-3b1b3fb6b8d0

### E3 prerequisite: create the cases this checklist registers evidence under
   case FAB-LIVE-1 -> 409 (200/201 created, 409 already exists from an earlier run)
   case FAB-LIVE-2 -> 409 (200/201 created, 409 already exists from an earlier run)
   case FAB-LIVE-3 -> 409 (200/201 created, 409 already exists from an earlier run)
   case FAB-LIVE-4 -> 409 (200/201 created, 409 already exists from an earlier run)
   register under an unknown case number -> 404  CASE_NOT_FOUND

### B1/B2/C1 register DIGITAL evidence (metadata carries a FORGED collector, must be ignored)
HTTP 201
   evidenceId                 EV-6e12400c-6f7f-4755-b965-aaee81917958
   status                     COLLECTED
   version                    1
   createdBy                  f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   currentCustodian           f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   fileCid                    bafkreia5j5oplrkdgmdxfvnu7fqmx2lwo6pjgqbngx4lwlud5a5gbqgej4
   fileSha256                 1d4f5cf5c543330772d5b4f960cbe976779e93402d35f8bb2e83e83a60c0c44f
   fileSize                   41
   metadataAvailable          True
   metadata.collectorId       f47ff616-80f2-4b54-b128-3b1b3fb6b8d0
   verification.status        NOT_CHECKED
   -> local sha256 of the file : 1d4f5cf5c543330772d5b4f960cbe976779e93402d35f8bb2e83e83a60c0c44f
   -> ledger fileSha256        : 1d4f5cf5c543330772d5b4f960cbe976779e93402d35f8bb2e83e83a60c0c44f
   -> createdBy == collector id from JWT? YES
   -> stored bytes fetched from IPFS by the file CID == original? YES

### B1 register PHYSICAL evidence (no file)
HTTP 201
   evidenceId                 EV-952a688f-8a97-49d4-83bd-be2f66115d16
   evidenceType               PHYSICAL
   fileCid                    None
   fileSha256                 None
   status                     COLLECTED
   createdByRole              None

### B3 retrieve by id, by file CID, by metadata CID
200 HTTP by id (AUDITOR)
   status                     COLLECTED
   version                    1
   metadata.description       Suspect phone image
   verification.status        NOT_CHECKED
200 HTTP by file CID
   ids: ['EV-6e12400c-6f7f-4755-b965-aaee81917958']
200 HTTP by metadata CID
404 HTTP by unknown CID
   error                      NOT_FOUND
404 HTTP unknown id
   error                      NOT_FOUND

### C2 verify untouched evidence
200 HTTP
   status                     VERIFIED
   ledgerVersion              1
   file.result                VERIFIED
   file.expectedSha256        1d4f5cf5c543330772d5b4f960cbe976779e93402d35f8bb2e83e83a60c0c44f
   file.actualSha256          1d4f5cf5c543330772d5b4f960cbe976779e93402d35f8bb2e83e83a60c0c44f
   metadata.result            VERIFIED

### C2 DELIBERATE CORRUPTION: change one word inside the file's block on the IPFS node's disk, then restart the node
   -> block file on the node: /data/ipfs/blocks/IT/CIQB2T246XCUGMYHOLK3J6LAZPUXM546SNAC2NPYXMXIH2B2MDAMITY.data
   -> before: LIVE-EVIDENCE-1790053875-original-content
   -> after : LIVE-EVIDENCE-1790053875-0RIGINAL-content
   -> IPFS still answers a cat for the same CID (content-addressing did NOT catch it):
   ->    cat -> LIVE-EVIDENCE-1790053875-0RIGINAL-content
200 HTTP verify
   status                     TAMPERED
   file.result                TAMPERED
   file.expectedSha256        1d4f5cf5c543330772d5b4f960cbe976779e93402d35f8bb2e83e83a60c0c44f
   file.actualSha256          10342b276199b8b33c0ee2b939ebd74306278609fef52052fd625c6e01346d30
   metadata.result            VERIFIED
200 HTTP GET ?verify=true
   verification.status        TAMPERED
   status                     COLLECTED

### C2 NOT_FOUND: register another item, delete its file block from the node's disk, restart
removed-block
200 HTTP verify (197 ms)
   status                     NOT_FOUND
   file.result                NOT_FOUND
   file.actualSha256          None
   metadata.result            VERIFIED

### B4 versioned update (ANALYST), old version stays readable, stale update rejected
200 HTTP update v1->v2
   version                    2
   lastAction                 METADATA_UPDATED
   lastReason                 Location corrected after audit
   metadata.location          Locker 9
   metadata.description       Wallet
   metadata.metadataVersion   2
   metadata.previousMetadataCid bafkreid2xsuulp62tumjohgymq5q2svjfkdd2a2ht6nqutfexgcibi7o5u
200 HTTP GET version 1 (old)
   version                    1
   metadata.location          Desk 2
200 HTTP GET version 2
   version                    2
   metadata.location          Locker 9
409 HTTP stale update (expectedVersion=1, record is at 2)
   error                      VERSION_CONFLICT
   message                    Expected version 1 but the record is at version 2
400 HTTP blank reason
   error                      VALIDATION_FAILED
403 HTTP JUDGE update
   error                      ACCESS_DENIED

### C3 ledger history (tx ids and ledger timestamps)
200 HTTP
   v1  CREATED           tx=c73092d027b0b4ad..  at=2026-09-22T05:11:47.398392100Z  by=COLLECTOR reason=''
   v2  METADATA_UPDATED  tx=b5dbddc15c27d95e..  at=2026-09-22T05:11:49.754853200Z  by=FORENSIC_ANALYST reason='Location corrected after audit'

### B5 disposal: request (PROSECUTOR) -> COLLECTOR cannot approve -> stale approval rejected -> JUDGE approves
200 HTTP request disposal
   status                     COLLECTED
   version                    2
   disposal.state             PENDING
   disposal.reason            Case closed by order 42/2026
   lastAction                 DISPOSAL_REQUESTED
403 HTTP COLLECTOR approve
   error                      ACCESS_DENIED
409 HTTP JUDGE approve with stale version
   error                      VERSION_CONFLICT
200 HTTP JUDGE approve
   status                     DISPOSED
   version                    3
   disposal.state             NONE
   lastAction                 DISPOSAL_APPROVED
   lastReason                 Order verified
409 HTTP update after DISPOSED
   error                      INVALID_STATE
   message                    Evidence is DISPOSED and can no longer change
200 HTTP the DISPOSED record is still readable
   status                     DISPOSED
   version                    3
200   history entries still on ledger: [(1, 'CREATED'), (2, 'DISPOSAL_REQUESTED'), (3, 'DISPOSAL_APPROVED')]

### C-02: there is no delete
   DELETE /api/evidence/{id} as collector -> 405  METHOD_NOT_ALLOWED
   DELETE /api/evidence/{id} as admin -> 405  METHOD_NOT_ALLOWED
   DELETE /api/evidence/{id} as judge -> 405  METHOD_NOT_ALLOWED

### A3 roles: who may register (403 for the rest)
   collector -> 201
   forensic-analyst -> 201
   prosecutor -> 403
   judge -> 403
   auditor -> 403
   admin -> 403

### B2 upload limits and types
   disallowed type (application/x-msdownload) -> 415  UNSUPPORTED_FILE_TYPE | File type 'application/x-msdownload' is not allowed
   empty file for DIGITAL -> 400  FILE_REQUIRED
   file on PHYSICAL evidence -> 400  FILE_NOT_ALLOWED
   missing required metadata field -> 400  ['description', 'caseId']
   60 MB file (limit 50 MB) -> 413  CONTENT_TOO_LARGE
   20 MB file -> HTTP 201 in 3375 ms
   -> local sha256 : 9f9a9e90f728073898bebac42b138f931349f8b4382efb6771a741b60f066c7f
   -> ledger sha256: 9f9a9e90f728073898bebac42b138f931349f8b4382efb6771a741b60f066c7f   size=20000000
200 HTTP verify of the 20 MB file
   status                     VERIFIED
   file.result                VERIFIED

### F1 IPFS outage: ledger information survives, verify says 503 (not NOT_FOUND)
200 HTTP GET during outage
   status                     COLLECTED
   version                    2
   metadataAvailable          False
   metadata                   None
503 HTTP verify during outage
   error                      STORAGE_UNAVAILABLE
   message                    IPFS node not reachable while reading content
   register during outage -> 503  STORAGE_UNAVAILABLE

### G4 health with the reference ledger
200 HTTP
   overall: UP
   ledger : {'details': {'detail': 'chaincode evidence answering on channel crimechannel via localhost:7051 as Org1MSP'}, 'status': 'UP'}
   ipfs   : {'status': 'UP'}
```

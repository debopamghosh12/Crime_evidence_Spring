package main

import (
	"encoding/json"
	"go/ast"
	"go/parser"
	"go/token"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"

	"github.com/hyperledger/fabric-contract-api-go/contractapi"
)

// These scenarios mirror InMemoryLedgerServiceTest (the Java executable spec) one for one, so the real
// chaincode and the reference ledger are held to the same expectations. Error codes are asserted, not
// message text.

const (
	org1 = "Org1MSP"

	collectorID  = "11111111-1111-1111-1111-111111111111"
	analystID    = "22222222-2222-2222-2222-222222222222"
	prosecutorID = "33333333-3333-3333-3333-333333333333"
	judgeID      = "44444444-4444-4444-4444-444444444444"
	auditorID    = "55555555-5555-5555-5555-555555555555"
	adminID      = "66666666-6666-6666-6666-666666666666"
)

var (
	sha   = strings.Repeat("a", 64)
	meta1 = "b" + strings.Repeat("a", 52)
	meta2 = "b" + strings.Repeat("b", 52)
	meta3 = "b" + strings.Repeat("c", 52)
	file1 = "b" + strings.Repeat("d", 52)
	c     = &EvidenceContract{}
)

var actors = map[string]string{
	roleCollector: collectorID, roleForensicAnalyst: analystID, roleProsecutor: prosecutorID,
	roleJudge: judgeID, roleAuditor: auditorID, roleAdmin: adminID,
}

var idCounter = 0

func newID() string {
	idCounter++
	return "EV-00000000-0000-4000-8000-" + strings.Repeat("0", 12-len(itoa(idCounter))) + itoa(idCounter)
}

// ---- thin wrappers so each test reads like the Java one -----------------------------------------------

// A2: every wrapper below presents a certificate naming the SAME id/role it passes as chaincode arguments
// (invokeAs), exactly as a correctly enrolled real identity would - so these tests keep exercising the
// role-PERMISSION logic (who may do what) unchanged; identity_test.go covers the certificate checks themselves.
func (l *fakeLedger) create(id, typ, mCid, fCid, fSha, fSize, role string) (*TxResult, error) {
	var res *TxResult
	err := l.invokeAs(org1, actors[role], role, func(ctx contractapi.TransactionContextInterface) (e error) {
		res, e = c.CreateEvidence(ctx, id, "CASE-1", typ, mCid, sha, fCid, fSha, fSize, actors[role], role)
		return
	})
	return res, err
}

func (l *fakeLedger) createDigital(t *testing.T) string {
	t.Helper()
	id := newID()
	if _, err := l.create(id, typeDigital, meta1, file1, sha, "10", roleCollector); err != nil {
		t.Fatalf("create failed: %v", err)
	}
	return id
}

func (l *fakeLedger) update(id, ver, cidv, reason, role string) error {
	return l.invokeAs(org1, actors[role], role, func(ctx contractapi.TransactionContextInterface) error {
		_, e := c.UpdateEvidence(ctx, id, ver, cidv, sha, reason, actors[role], role)
		return e
	})
}
func (l *fakeLedger) status(id, ver, to, role string) error {
	return l.invokeAs(org1, actors[role], role, func(ctx contractapi.TransactionContextInterface) error {
		_, e := c.UpdateStatus(ctx, id, ver, to, "r", actors[role], role)
		return e
	})
}
func (l *fakeLedger) request(id, ver, reason, role string) error {
	return l.invokeAs(org1, actors[role], role, func(ctx contractapi.TransactionContextInterface) error {
		_, e := c.RequestDisposal(ctx, id, ver, reason, actors[role], role)
		return e
	})
}
func (l *fakeLedger) approve(id, ver, role string) error {
	return l.invokeAs(org1, actors[role], role, func(ctx contractapi.TransactionContextInterface) error {
		_, e := c.ApproveDisposal(ctx, id, ver, "ok", actors[role], role)
		return e
	})
}
func (l *fakeLedger) reject(id, ver, role string) error {
	return l.invokeAs(org1, actors[role], role, func(ctx contractapi.TransactionContextInterface) error {
		_, e := c.RejectDisposal(ctx, id, ver, "no", actors[role], role)
		return e
	})
}
func (l *fakeLedger) get(id string) (rec *EvidenceRecord, err error) {
	err = l.invoke(org1, func(ctx contractapi.TransactionContextInterface) (e error) {
		rec, e = c.GetEvidence(ctx, id)
		return
	})
	return
}
func (l *fakeLedger) mustGet(t *testing.T, id string) *EvidenceRecord {
	t.Helper()
	r, err := l.get(id)
	if err != nil {
		t.Fatalf("get failed: %v", err)
	}
	return r
}
func (l *fakeLedger) hist(id string) (h []*HistoryEntry, err error) {
	err = l.invoke(org1, func(ctx contractapi.TransactionContextInterface) (e error) {
		h, e = c.GetHistory(ctx, id)
		return
	})
	return
}
func (l *fakeLedger) byCid(cid string) (ids []string, err error) {
	err = l.invoke(org1, func(ctx contractapi.TransactionContextInterface) (e error) {
		ids, e = c.FindByCid(ctx, cid)
		return
	})
	return
}

func wantCode(t *testing.T, err error, code string) {
	t.Helper()
	if err == nil {
		t.Fatalf("expected %s, got success", code)
	}
	if !strings.HasPrefix(err.Error(), code+":") {
		t.Fatalf("expected %s, got %q", code, err.Error())
	}
}

func mustOK(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

// ---------------------------------------------------------------------------------------------- create

func TestCreateStoresVersionOneCollectedWithLedgerTimestampAndActor(t *testing.T) {
	l := newFakeLedger()
	id := newID()
	tx, err := l.create(id, typeDigital, meta1, file1, sha, "10", roleCollector)
	mustOK(t, err)

	r := l.mustGet(t, id)
	if r.Version != 1 || r.Status != statusCollected || r.LastAction != actionCreated {
		t.Fatalf("unexpected record %+v", r)
	}
	if r.CreatedBy != collectorID || r.CurrentCustodian != collectorID || r.CreatedByRole != roleCollector {
		t.Fatalf("actor not recorded: %+v", r)
	}
	if r.CreatedAt != "2026-03-01T10:00:01Z" || r.UpdatedAt != r.CreatedAt { // the TRANSACTION's time (C4)
		t.Fatalf("timestamp is not the transaction's: %s", r.CreatedAt)
	}
	if len(tx.TxID) != 64 || tx.Version != 1 || tx.Timestamp != r.CreatedAt {
		t.Fatalf("bad TxResult %+v", tx)
	}
	if r.FileSha256 != sha || r.FileSize != 10 || r.Disposal.State != disposalNone {
		t.Fatalf("unexpected file/disposal fields: %+v", r)
	}
}

func TestDuplicateIdIsRejected(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	_, err := l.create(id, typeDigital, meta2, file1, sha, "10", roleCollector)
	wantCode(t, err, errExists)
}

func TestOnlyCollectorAndAnalystMayCreate(t *testing.T) {
	l := newFakeLedger()
	for role := range knownRoles {
		_, err := l.create(newID(), typeDigital, meta1, file1, sha, "10", role)
		if role == roleCollector || role == roleForensicAnalyst {
			mustOK(t, err)
		} else {
			wantCode(t, err, errForbiddenRole)
		}
	}
}

func TestDigitalNeedsFileFieldsAndPhysicalForbidsThem(t *testing.T) {
	l := newFakeLedger()
	_, err := l.create(newID(), typeDigital, meta1, "", "", "", roleCollector)
	wantCode(t, err, errInvalidArgument)
	_, err = l.create(newID(), typePhysical, meta1, file1, sha, "5", roleCollector)
	wantCode(t, err, errInvalidArgument)
	_, err = l.create(newID(), typeDigital, meta1, file1, sha, "0", roleCollector)
	wantCode(t, err, errInvalidArgument)
	_, err = l.create(newID(), typePhysical, meta1, "", "", "", roleCollector)
	mustOK(t, err)
}

func TestMalformedInputsAreRejected(t *testing.T) {
	l := newFakeLedger()
	for name, call := range map[string]func() error{
		"bad id": func() error {
			_, e := l.create("EV-not-a-uuid", typeDigital, meta1, file1, sha, "10", roleCollector)
			return e
		},
		"bad cid": func() error {
			_, e := l.create(newID(), typePhysical, "not-a-cid", "", "", "", roleCollector)
			return e
		},
		"bad type": func() error { _, e := l.create(newID(), "OTHER", meta1, "", "", "", roleCollector); return e },
		"upper-case sha": func() error {
			return l.invokeAs(org1, collectorID, roleCollector, func(ctx contractapi.TransactionContextInterface) error {
				_, e := c.CreateEvidence(ctx, newID(), "C", typePhysical, meta1, strings.Repeat("A", 64), "", "", "", collectorID, roleCollector)
				return e
			})
		},
		"bad actor id": func() error {
			return l.invoke(org1, func(ctx contractapi.TransactionContextInterface) error {
				_, e := c.CreateEvidence(ctx, newID(), "C", typePhysical, meta1, sha, "", "", "", "not-a-uuid", roleCollector)
				return e
			})
		},
		"unknown role": func() error {
			return l.invoke(org1, func(ctx contractapi.TransactionContextInterface) error {
				_, e := c.CreateEvidence(ctx, newID(), "C", typePhysical, meta1, sha, "", "", "", collectorID, "SUPERUSER")
				return e
			})
		},
	} {
		if err := call(); err == nil || !strings.HasPrefix(err.Error(), errInvalidArgument+":") {
			t.Errorf("%s: expected INVALID_ARGUMENT, got %v", name, err)
		}
	}
}

func TestAnOrganisationOutsideTheAllowListCannotWrite(t *testing.T) {
	l := newFakeLedger()
	err := l.invoke("Org3MSP", func(ctx contractapi.TransactionContextInterface) error {
		_, e := c.CreateEvidence(ctx, newID(), "C", typePhysical, meta1, sha, "", "", "", collectorID, roleCollector)
		return e
	})
	wantCode(t, err, errForbiddenRole)
}

// ---------------------------------------------------------------------------------------------- update

func TestUpdateBumpsVersionKeepsFileFieldsAndNeedsAReason(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	before := *l.mustGet(t, id)

	wantCode(t, l.update(id, "1", meta2, "  ", roleForensicAnalyst), errInvalidArgument)
	mustOK(t, l.update(id, "1", meta2, "fix typo", roleForensicAnalyst))

	after := l.mustGet(t, id)
	if after.Version != 2 || after.MetadataCid != meta2 || after.LastReason != "fix typo" {
		t.Fatalf("update not applied: %+v", after)
	}
	if after.UpdatedBy != analystID || after.CreatedBy != collectorID {
		t.Fatalf("creator must never be rewritten: %+v", after)
	}
	// Immutability of the evidence file (design section 3, DECISIONS D-021).
	if after.FileCid != before.FileCid || after.FileSha256 != before.FileSha256 || after.FileSize != before.FileSize {
		t.Fatalf("file fields changed: before %+v after %+v", before, after)
	}
}

func TestStaleExpectedVersionIsRejected(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	mustOK(t, l.update(id, "1", meta2, "r", roleCollector))
	wantCode(t, l.update(id, "1", meta3, "r", roleCollector), errVersionConflict)
	wantCode(t, l.update(id, "abc", meta3, "r", roleCollector), errInvalidArgument)
	wantCode(t, l.update(id, "0", meta3, "r", roleCollector), errInvalidArgument)
}

func TestUpdateWithUnchangedCidOrUnknownIdOrWrongRoleFails(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	wantCode(t, l.update(id, "1", meta1, "r", roleCollector), errInvalidArgument)
	wantCode(t, l.update(newID(), "1", meta2, "r", roleCollector), errNotFound)
	wantCode(t, l.update(id, "1", meta2, "r", roleJudge), errForbiddenRole)
	wantCode(t, l.update(id, "1", meta2, "r", roleAdmin), errForbiddenRole)
}

// ----------------------------------------------------------------------------------------------- status

func TestStatusMovesForwardOnlyAndNeverToDisposed(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	mustOK(t, l.status(id, "1", statusProcessing, roleForensicAnalyst))
	wantCode(t, l.status(id, "2", statusArchived, roleForensicAnalyst), errInvalidState)
	wantCode(t, l.status(id, "2", statusCollected, roleForensicAnalyst), errInvalidState)
	wantCode(t, l.status(id, "2", statusDisposed, roleProsecutor), errInvalidArgument)
	wantCode(t, l.status(id, "2", statusAnalyzed, roleJudge), errForbiddenRole)
	mustOK(t, l.status(id, "2", statusAnalyzed, roleProsecutor))
}

// --------------------------------------------------------------------------------------------- disposal

func TestDisposalNeedsRequestThenJudgeApprovalThenFreezesTheRecordButKeepsIt(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)

	wantCode(t, l.approve(id, "1", roleJudge), errInvalidState) // nothing to approve
	wantCode(t, l.request(id, "1", "why", roleJudge), errForbiddenRole)
	mustOK(t, l.request(id, "1", "case closed", roleProsecutor))
	if r := l.mustGet(t, id); r.Disposal.State != disposalPending || r.Disposal.RequestedBy != prosecutorID {
		t.Fatalf("request not recorded: %+v", r.Disposal)
	}
	wantCode(t, l.request(id, "2", "again", roleCollector), errInvalidState) // already pending
	wantCode(t, l.approve(id, "2", roleProsecutor), errForbiddenRole)        // only a judge decides
	wantCode(t, l.approve(id, "1", roleJudge), errVersionConflict)           // must have reviewed the current version

	mustOK(t, l.approve(id, "2", roleJudge))
	r := l.mustGet(t, id)
	if r.Status != statusDisposed || r.Version != 3 {
		t.Fatalf("not disposed: %+v", r)
	}
	if h, err := l.hist(id); err != nil || len(h) != 3 { // the record and its history remain
		t.Fatalf("history lost after disposal: %v %d", err, len(h))
	}
	// Frozen against every further write.
	wantCode(t, l.update(id, "3", meta2, "r", roleCollector), errInvalidState)
	wantCode(t, l.request(id, "3", "r", roleCollector), errInvalidState)
	wantCode(t, l.status(id, "3", statusProcessing, roleCollector), errInvalidState)
	wantCode(t, l.reject(id, "3", roleJudge), errInvalidState)
}

func TestRejectedDisposalLeavesTheRecordUsable(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	mustOK(t, l.request(id, "1", "r", roleCollector))
	mustOK(t, l.reject(id, "2", roleJudge))
	r := l.mustGet(t, id)
	if r.Status != statusCollected || r.Disposal.State != disposalNone {
		t.Fatalf("reject should restore usability: %+v", r)
	}
	mustOK(t, l.update(id, "3", meta2, "still editable", roleCollector))
}

func TestTheApproverCannotBeTheRequester(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	// Roles are disjoint in the approved table, so force the case by recording the judge as requester.
	rec := l.mustGet(t, id)
	rec.Disposal = Disposal{State: disposalPending, RequestedBy: judgeID, Reason: "x"}
	raw, _ := json.Marshal(rec)
	l.state[recordKey(id)] = raw
	wantCode(t, l.approve(id, "1", roleJudge), errForbiddenRole)
}

// ---------------------------------------------------------------------------- history, index, atomicity

func TestHistoryIsOldestFirstWithDistinctTxIdsEvenIfThePeerReturnsNewestFirst(t *testing.T) {
	for _, newestFirst := range []bool{false, true} {
		l := newFakeLedger()
		l.historyNewestFirst = newestFirst
		id := l.createDigital(t)
		mustOK(t, l.update(id, "1", meta2, "r", roleCollector))
		mustOK(t, l.request(id, "2", "r", roleCollector))

		h, err := l.hist(id)
		mustOK(t, err)
		if len(h) != 3 {
			t.Fatalf("want 3 entries, got %d", len(h))
		}
		wantActions := []string{actionCreated, actionMetadataUpdated, actionDisposalRequested}
		seen := map[string]bool{}
		for i, e := range h {
			if e.Record.Version != i+1 || e.Record.LastAction != wantActions[i] {
				t.Fatalf("entry %d out of order (newestFirst=%v): %+v", i, newestFirst, e.Record)
			}
			if seen[e.TxID] || len(e.TxID) != 64 || e.Timestamp == "" {
				t.Fatalf("bad tx id/timestamp: %+v", e)
			}
			seen[e.TxID] = true
		}
		if h[0].Record.MetadataCid != meta1 || h[1].Record.MetadataCid != meta2 { // old version still readable
			t.Fatalf("old version not preserved")
		}
	}
	l := newFakeLedger()
	_, err := l.hist(newID())
	wantCode(t, err, errNotFound)
}

func TestCidIndexFindsFileAndEveryMetadataCidAndSharedFiles(t *testing.T) {
	l := newFakeLedger()
	a, b := l.createDigital(t), l.createDigital(t) // same file CID
	mustOK(t, l.update(a, "1", meta2, "r", roleCollector))

	check := func(cid string, want ...string) {
		t.Helper()
		got, err := l.byCid(cid)
		mustOK(t, err)
		sort.Strings(got)
		sort.Strings(want)
		if len(got) == 0 && len(want) == 0 { // empty result: the chaincode returns [] (never null), the test passes nil
			return
		}
		if !reflect.DeepEqual(got, want) {
			t.Fatalf("cid %s: got %v want %v", cid[:8], got, want)
		}
	}
	check(file1, a, b)
	check(meta2, a)
	check(meta1, a, b) // old CIDs stay indexed
	check("b" + strings.Repeat("z", 52))
	_, err := l.byCid("not-a-cid")
	wantCode(t, err, errInvalidArgument)
}

func TestARejectedCallChangesNothing(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	before := len(l.state)
	histBefore := len(l.history[recordKey(id)])

	wantCode(t, l.update(id, "9", meta2, "r", roleCollector), errVersionConflict)
	wantCode(t, l.update(id, "1", meta2, "", roleCollector), errInvalidArgument)
	_, err := l.create(id, typeDigital, meta2, file1, sha, "10", roleCollector)
	wantCode(t, err, errExists)

	if len(l.state) != before || len(l.history[recordKey(id)]) != histBefore {
		t.Fatal("a rejected transaction modified the ledger")
	}
	if r := l.mustGet(t, id); r.Version != 1 || r.MetadataCid != meta1 {
		t.Fatalf("record changed: %+v", r)
	}
}

func TestEveryWriteEmitsOneEventWithOnlyIdentifiers(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	mustOK(t, l.update(id, "1", meta2, "r", roleCollector))
	mustOK(t, l.status(id, "2", statusProcessing, roleCollector))
	mustOK(t, l.request(id, "3", "r", roleCollector))
	mustOK(t, l.reject(id, "4", roleJudge))
	mustOK(t, l.request(id, "5", "r", roleCollector))
	mustOK(t, l.approve(id, "6", roleJudge))

	var names []string
	for _, e := range l.events {
		names = append(names, e.name)
		var p map[string]any
		mustOK(t, json.Unmarshal(e.payload, &p))
		keys := []string{}
		for k := range p {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		if !reflect.DeepEqual(keys, []string{"action", "evidenceId", "txId", "version"}) {
			t.Fatalf("event payload has unexpected fields: %v", keys)
		}
	}
	want := []string{"Evidence.Created", "Evidence.MetadataUpdated", "Evidence.StatusChanged",
		"Evidence.DisposalRequested", "Evidence.DisposalRejected", "Evidence.DisposalRequested", "Evidence.DisposalApproved"}
	if !reflect.DeepEqual(names, want) {
		t.Fatalf("events %v, want %v", names, want)
	}
}

// -------------------------------------------------------------------------------- C-02 and contract shape

func TestNothingEverDeletes(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	mustOK(t, l.update(id, "1", meta2, "r", roleCollector))
	mustOK(t, l.request(id, "2", "r", roleCollector))
	mustOK(t, l.approve(id, "3", roleJudge))
	if l.delCalls != 0 {
		t.Fatalf("DelState was called %d times", l.delCalls)
	}
	// Inspect the syntax tree, not the text: a comment that mentions DelState is fine, a call is not.
	files, _ := filepath.Glob("*.go")
	fset := token.NewFileSet()
	for _, f := range files {
		if strings.HasSuffix(f, "_test.go") {
			continue
		}
		parsed, err := parser.ParseFile(fset, f, nil, 0)
		mustOK(t, err)
		ast.Inspect(parsed, func(n ast.Node) bool {
			if sel, ok := n.(*ast.SelectorExpr); ok && (sel.Sel.Name == "DelState" || sel.Sel.Name == "PurgePrivateData" ||
				sel.Sel.Name == "DelPrivateData") {
				t.Errorf("%s calls %s", f, sel.Sel.Name)
			}
			return true
		})
	}
}

func TestTheExportedFunctionSetIsExactlyTheApprovedOne(t *testing.T) {
	typ := reflect.TypeOf(c)
	var got []string
	for i := 0; i < typ.NumMethod(); i++ {
		got = append(got, typ.Method(i).Name)
	}
	sort.Strings(got)
	want := []string{"AcceptTransfer", "ApproveDisposal", "CancelTransfer", "CreateEvidence", "FindByCid", "FindPendingTransfers",
		"GetEvidence", "GetHistory", "InitiateTransfer", "RejectDisposal", "RejectTransfer", "RequestDisposal", "UpdateEvidence", "UpdateStatus"}
	// contractapi.Contract contributes its own helper methods; keep only ours by name.
	var ours []string
	for _, n := range got {
		for _, w := range want {
			if n == w {
				ours = append(ours, n)
			}
		}
	}
	if !reflect.DeepEqual(ours, want) {
		t.Fatalf("functions %v, want %v", ours, want)
	}
	for _, n := range got {
		low := strings.ToLower(n)
		if strings.Contains(low, "delete") || strings.Contains(low, "remove") || strings.Contains(low, "purge") {
			t.Fatalf("delete-like function %s", n)
		}
	}
}

// The Java side parses these exact keys (FabricLedgerService); renaming one silently breaks the backend.
func TestRecordJsonKeysAreTheContractWithTheJavaSide(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	raw, _ := json.Marshal(l.mustGet(t, id))
	var m map[string]any
	mustOK(t, json.Unmarshal(raw, &m))
	var keys []string
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	want := []string{"caseId", "createdAt", "createdBy", "createdByRole", "currentCustodian", "disposal", "docType",
		"evidenceId", "evidenceType", "fileCid", "fileSha256", "fileSize", "lastAction", "lastReason", "metadataCid",
		"metadataSha256", "status", "transfer", "updatedAt", "updatedBy", "updatedByRole", "version"}
	if !reflect.DeepEqual(keys, want) {
		t.Fatalf("record keys changed:\n got %v\nwant %v", keys, want)
	}
}

func TestContractMetadataGenerates(t *testing.T) {
	// contractapi builds a schema from the function signatures when the chaincode starts; a signature it
	// cannot describe fails here instead of on the peer.
	if _, err := contractapi.NewChaincode(&EvidenceContract{}); err != nil {
		t.Fatalf("contract metadata: %v", err)
	}
}

// Regression for a defect that unit tests with a fake stub could not see: contractapi validates every
// returned value against a schema where fields are required unless tagged metadata:",optional".
func TestOmitemptyFieldsAreAlsoOptionalInTheSchema(t *testing.T) {
	for _, typ := range []reflect.Type{reflect.TypeOf(EvidenceRecord{}), reflect.TypeOf(Disposal{})} {
		for i := 0; i < typ.NumField(); i++ {
			f := typ.Field(i)
			if strings.Contains(f.Tag.Get("json"), "omitempty") && !strings.Contains(f.Tag.Get("metadata"), "optional") {
				t.Errorf("%s.%s is omitempty but not marked optional: a read would fail schema validation on a real peer",
					typ.Name(), f.Name)
			}
		}
	}
}

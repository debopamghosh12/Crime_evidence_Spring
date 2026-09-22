package main

import (
	"encoding/json"
	"reflect"
	"sort"
	"testing"

	"github.com/hyperledger/fabric-contract-api-go/contractapi"
)

// D2 custody transfer, held to the same expectations as the Java InMemoryLedgerService.

// A2: as in evidence_test.go, these present a certificate matching the args they pass, so they keep exercising
// the custody-permission logic unchanged.
func (l *fakeLedger) initiate(id, ver, toID, toRole, reason, role string) error {
	return l.invokeAs(org1, actors[role], role, func(ctx contractapi.TransactionContextInterface) error {
		_, e := c.InitiateTransfer(ctx, id, ver, toID, toRole, reason, "sealed bag no. 7", actors[role], role)
		return e
	})
}
func (l *fakeLedger) respond(fn func(ctx contractapi.TransactionContextInterface, id, ver, note, actor, role string) (*TxResult, error),
	id, ver, role string) error {
	return l.invokeAs(org1, actors[role], role, func(ctx contractapi.TransactionContextInterface) error {
		_, e := fn(ctx, id, ver, "note", actors[role], role)
		return e
	})
}
func (l *fakeLedger) accept(id, ver, role string) error {
	return l.respond(c.AcceptTransfer, id, ver, role)
}
func (l *fakeLedger) rejectT(id, ver, role string) error {
	return l.respond(c.RejectTransfer, id, ver, role)
}
func (l *fakeLedger) cancel(id, ver, role string) error {
	return l.respond(c.CancelTransfer, id, ver, role)
}
func (l *fakeLedger) pending(userID string) (ids []string, err error) {
	err = l.invoke(org1, func(ctx contractapi.TransactionContextInterface) (e error) {
		ids, e = c.FindPendingTransfers(ctx, userID)
		return
	})
	return
}

func TestInitiateOnlyByTheCurrentCustodianAndCustodyMovesOnlyOnAcceptance(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)                                                                                                // custodian: collector
	wantCode(t, l.initiate(id, "1", analystID, roleForensicAnalyst, "lab analysis", roleForensicAnalyst), errForbiddenRole) // analyst is not the custodian
	mustOK(t, l.initiate(id, "1", analystID, roleForensicAnalyst, "lab analysis", roleCollector))

	r := l.mustGet(t, id)
	if r.Transfer.State != transferPending || r.Transfer.From != collectorID || r.Transfer.To != analystID ||
		r.Transfer.ToRole != roleForensicAnalyst || r.Transfer.Notes != "sealed bag no. 7" || r.Transfer.InitiatedAt == "" {
		t.Fatalf("transfer not recorded: %+v", r.Transfer)
	}
	if r.CurrentCustodian != collectorID || r.LastAction != actionTransferInitiated || r.Version != 2 {
		t.Fatalf("custody must not move before acceptance: %+v", r)
	}
	if l.events[len(l.events)-1].name != "Evidence.TransferInitiated" {
		t.Fatalf("wrong event %s", l.events[len(l.events)-1].name)
	}
}

func TestAcceptMakesTheReceiverCustodianAndTheyCanHandOnWhileTheOldCustodianCannot(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	mustOK(t, l.initiate(id, "1", analystID, roleForensicAnalyst, "analysis", roleCollector))
	mustOK(t, l.accept(id, "2", roleForensicAnalyst))

	r := l.mustGet(t, id)
	if r.CurrentCustodian != analystID || r.Transfer.State != transferAccepted || r.Transfer.ResolvedAt == "" ||
		r.Transfer.ResolutionNote != "note" || r.LastAction != actionTransferAccepted {
		t.Fatalf("accept not applied: %+v", r)
	}
	wantCode(t, l.initiate(id, "3", prosecutorID, roleProsecutor, "x", roleCollector), errForbiddenRole) // old custodian
	mustOK(t, l.initiate(id, "3", prosecutorID, roleProsecutor, "to prosecution", roleForensicAnalyst))  // new custodian
	if r.CreatedBy != collectorID {
		t.Fatalf("the creator must never be rewritten")
	}
}

func TestRejectLeavesCustodyWithTheSenderAndAllowsANewTransfer(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	mustOK(t, l.initiate(id, "1", analystID, roleForensicAnalyst, "r", roleCollector))
	mustOK(t, l.rejectT(id, "2", roleForensicAnalyst))
	r := l.mustGet(t, id)
	if r.CurrentCustodian != collectorID || r.Transfer.State != transferRejected {
		t.Fatalf("reject wrong: %+v", r)
	}
	mustOK(t, l.initiate(id, "3", prosecutorID, roleProsecutor, "try someone else", roleCollector))
}

func TestOnlyTheSenderCanCancelAndOnlyTheNamedReceiverCanRespond(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	mustOK(t, l.initiate(id, "1", analystID, roleForensicAnalyst, "r", roleCollector))

	wantCode(t, l.cancel(id, "2", roleForensicAnalyst), errForbiddenRole) // the receiver cannot cancel
	wantCode(t, l.accept(id, "2", roleCollector), errForbiddenRole)       // the sender cannot accept
	wantCode(t, l.accept(id, "2", roleProsecutor), errForbiddenRole)      // a bystander cannot accept
	wantCode(t, l.rejectT(id, "2", roleJudge), errForbiddenRole)          // nor reject
	mustOK(t, l.cancel(id, "2", roleCollector))
	if r := l.mustGet(t, id); r.Transfer.State != transferCancelled || r.CurrentCustodian != collectorID {
		t.Fatalf("cancel wrong: %+v", r)
	}
	wantCode(t, l.cancel(id, "3", roleCollector), errInvalidState) // nothing pending any more
}

func TestTheReceiverMustRespondWithTheRoleTheTransferWasAddressedTo(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	mustOK(t, l.initiate(id, "1", analystID, roleForensicAnalyst, "r", roleCollector))
	// same user id, a different (still custody-capable) role: refused, the address named FORENSIC_ANALYST.
	// A contrived certificate (analystID enrolled as PROSECUTOR) is used deliberately, to reach past authorise()
	// (which only checks permission for the FUNCTION) into the custody logic's own addressed-role check.
	err := l.invokeAs(org1, analystID, roleProsecutor, func(ctx contractapi.TransactionContextInterface) error {
		_, e := c.AcceptTransfer(ctx, id, "2", "n", analystID, roleProsecutor)
		return e
	})
	wantCode(t, err, errForbiddenRole)
}

func TestRolesThatCannotHoldEvidenceAreRefusedEverywhere(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	wantCode(t, l.initiate(id, "1", analystID, roleForensicAnalyst, "r", roleAuditor), errForbiddenRole)
	wantCode(t, l.initiate(id, "1", analystID, roleForensicAnalyst, "r", roleAdmin), errForbiddenRole)
	wantCode(t, l.initiate(id, "1", auditorID, roleAuditor, "r", roleCollector), errInvalidArgument) // receiver role cannot hold
	wantCode(t, l.initiate(id, "1", adminID, roleAdmin, "r", roleCollector), errInvalidArgument)
	mustOK(t, l.initiate(id, "1", analystID, roleForensicAnalyst, "r", roleCollector))
	wantCode(t, l.accept(id, "2", roleAuditor), errForbiddenRole)
}

func TestInitiateArgumentAndStateChecks(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	wantCode(t, l.initiate(id, "1", collectorID, roleCollector, "r", roleCollector), errInvalidArgument)        // to yourself
	wantCode(t, l.initiate(id, "1", "not-a-uuid", roleForensicAnalyst, "r", roleCollector), errInvalidArgument) // malformed id
	wantCode(t, l.initiate(id, "1", analystID, roleForensicAnalyst, "  ", roleCollector), errInvalidArgument)   // blank reason
	wantCode(t, l.initiate(id, "9", analystID, roleForensicAnalyst, "r", roleCollector), errVersionConflict)    // stale
	wantCode(t, l.initiate(newID(), "1", analystID, roleForensicAnalyst, "r", roleCollector), errNotFound)
	err := l.invokeAs(org1, collectorID, roleCollector, func(ctx contractapi.TransactionContextInterface) error {
		_, e := c.InitiateTransfer(ctx, id, "1", analystID, roleForensicAnalyst, "r", string(make([]byte, maxNotesLen+1)), collectorID, roleCollector)
		return e
	})
	wantCode(t, err, errInvalidArgument) // notes too long

	mustOK(t, l.initiate(id, "1", analystID, roleForensicAnalyst, "r", roleCollector))
	wantCode(t, l.initiate(id, "2", prosecutorID, roleProsecutor, "r", roleCollector), errInvalidState) // one at a time
}

func TestATransferCannotStartWhileADisposalIsPendingOrAfterDisposal(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	mustOK(t, l.request(id, "1", "why", roleCollector))
	wantCode(t, l.initiate(id, "2", analystID, roleForensicAnalyst, "r", roleCollector), errInvalidState)
	mustOK(t, l.approve(id, "2", roleJudge))
	wantCode(t, l.initiate(id, "3", analystID, roleForensicAnalyst, "r", roleCollector), errInvalidState) // DISPOSED
}

func TestPendingListShowsOnlyWhatIsStillPendingTowardThatUser(t *testing.T) {
	l := newFakeLedger()
	a, b, cc := l.createDigital(t), l.createDigital(t), l.createDigital(t)
	mustOK(t, l.initiate(a, "1", analystID, roleForensicAnalyst, "r", roleCollector))
	mustOK(t, l.initiate(b, "1", analystID, roleForensicAnalyst, "r", roleCollector))
	mustOK(t, l.initiate(cc, "1", prosecutorID, roleProsecutor, "r", roleCollector))

	got, err := l.pending(analystID)
	mustOK(t, err)
	if !reflect.DeepEqual(sorted(got), sorted([]string{a, b})) {
		t.Fatalf("analyst pending = %v", got)
	}
	mustOK(t, l.accept(a, "2", roleForensicAnalyst)) // resolved -> drops off
	mustOK(t, l.cancel(b, "2", roleCollector))       // cancelled -> drops off
	got, _ = l.pending(analystID)
	if len(got) != 0 {
		t.Fatalf("resolved transfers must not be listed: %v", got)
	}
	if got, _ := l.pending(prosecutorID); !reflect.DeepEqual(got, []string{cc}) {
		t.Fatalf("prosecutor pending = %v", got)
	}
	if got, _ := l.pending(judgeID); got == nil || len(got) != 0 {
		t.Fatalf("no transfers must be an empty list, not null: %#v", got)
	}
	_, err = l.pending("nope")
	wantCode(t, err, errInvalidArgument)
}

func sorted(s []string) []string { out := append([]string{}, s...); sort.Strings(out); return out }

func TestARecordWrittenBeforeCustodyTransfersExistedReadsAsNoneAndCanBeTransferred(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	rec := l.mustGet(t, id)
	raw, _ := json.Marshal(rec)
	var m map[string]any
	_ = json.Unmarshal(raw, &m)
	delete(m, "transfer") // exactly how a v1.0/v1.1 record looks
	legacy, _ := json.Marshal(m)
	l.state[recordKey(id)] = legacy

	if r := l.mustGet(t, id); r.Transfer.State != transferNone {
		t.Fatalf("legacy record must read as NONE, got %q", r.Transfer.State)
	}
	mustOK(t, l.initiate(id, "1", analystID, roleForensicAnalyst, "r", roleCollector))
}

func TestTheCustodyTimelineIsRecoverableFromHistory(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)
	mustOK(t, l.initiate(id, "1", analystID, roleForensicAnalyst, "lab", roleCollector))
	mustOK(t, l.accept(id, "2", roleForensicAnalyst))
	mustOK(t, l.initiate(id, "3", prosecutorID, roleProsecutor, "court", roleForensicAnalyst))
	mustOK(t, l.rejectT(id, "4", roleProsecutor))

	h, err := l.hist(id)
	mustOK(t, err)
	var actions []string
	for _, e := range h {
		actions = append(actions, e.Record.LastAction)
	}
	want := []string{actionCreated, actionTransferInitiated, actionTransferAccepted, actionTransferInitiated, actionTransferRejected}
	if !reflect.DeepEqual(actions, want) {
		t.Fatalf("history actions %v want %v", actions, want)
	}
	if h[2].Record.CurrentCustodian != analystID || h[4].Record.CurrentCustodian != analystID {
		t.Fatalf("custody per version wrong")
	}
}

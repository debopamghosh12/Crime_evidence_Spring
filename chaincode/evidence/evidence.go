package main

import (
	"encoding/json"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/hyperledger/fabric-contract-api-go/contractapi"
)

// EvidenceContract implements the approved design in docs/CHAINCODE_DESIGN.md.
//
// Rules that every function upholds (each has a test):
//   - No function deletes anything (C-02). DelState is never called, and a test scans the source for it.
//   - Timestamps come only from the transaction (C4): never time.Now, which would differ between endorsers.
//   - Nothing is written before every check has passed. Fabric also discards the writes of a transaction
//     that returns an error, but validating first keeps that guarantee independent of the runtime.
//   - The evidence FILE fields are copied through unchanged by every update (immutability, D-021).
type EvidenceContract struct {
	contractapi.Contract
}

const (
	recordPrefix = "EV~"
	cidIndexType = "CID"
)

func recordKey(evidenceID string) string { return recordPrefix + evidenceID }

// ------------------------------------------------------------------------------------------------ writes

// CreateEvidence registers a new item at version 1 with status COLLECTED (B1, C1). The file arguments are
// empty strings for PHYSICAL evidence and all three are required for DIGITAL.
func (c *EvidenceContract) CreateEvidence(ctx contractapi.TransactionContextInterface, evidenceID, caseID,
	evidenceType, metadataCid, metadataSha256, fileCid, fileSha256, fileSize, actorID, actorRole string) (*TxResult, error) {

	actor, err := authorise(ctx, actorID, actorRole, canCreateOrUpdate, "create evidence")
	if err != nil {
		return nil, err
	}
	if !evidenceIDRe.MatchString(evidenceID) {
		return nil, fail(errInvalidArgument, "evidenceId is malformed")
	}
	if !caseIDRe.MatchString(caseID) {
		return nil, fail(errInvalidArgument, "caseId is malformed")
	}
	if evidenceType != typeDigital && evidenceType != typePhysical {
		return nil, fail(errInvalidArgument, "evidenceType must be DIGITAL or PHYSICAL")
	}
	if err := requireCidAndHash(metadataCid, metadataSha256, "metadata"); err != nil {
		return nil, err
	}
	var size int64
	if evidenceType == typeDigital {
		if err := requireCidAndHash(fileCid, fileSha256, "file"); err != nil {
			return nil, err
		}
		size, err = strconv.ParseInt(fileSize, 10, 64)
		if err != nil || size <= 0 {
			return nil, fail(errInvalidArgument, "fileSize must be positive for DIGITAL evidence")
		}
	} else if fileCid != "" || fileSha256 != "" || fileSize != "" {
		return nil, fail(errInvalidArgument, "PHYSICAL evidence must not carry file fields")
	}

	existing, err := ctx.GetStub().GetState(recordKey(evidenceID))
	if err != nil {
		return nil, fail(errInvalidState, "cannot read ledger state: %v", err)
	}
	if existing != nil {
		return nil, fail(errExists, "Evidence %s already exists", evidenceID)
	}

	ts, txID := stamp(ctx)
	record := &EvidenceRecord{
		DocType: "evidence", EvidenceID: evidenceID, CaseID: caseID, EvidenceType: evidenceType,
		Status: statusCollected, Version: 1, MetadataCid: metadataCid, MetadataSha256: metadataSha256,
		FileCid: fileCid, FileSha256: fileSha256, FileSize: size,
		CreatedBy: actor.id, CreatedByRole: actor.role, CreatedAt: ts,
		UpdatedBy: actor.id, UpdatedByRole: actor.role, UpdatedAt: ts,
		LastAction: actionCreated, CurrentCustodian: actor.id, Disposal: Disposal{State: disposalNone},
		Transfer: Transfer{State: transferNone},
	}
	if err := commit(ctx, record, txID, actionCreated); err != nil {
		return nil, err
	}
	// The index makes lookup-by-CID possible without CouchDB (LevelDB has no rich queries). Every CID ever
	// attached to an item stays indexed; entries are never removed.
	if err := indexCid(ctx, metadataCid, evidenceID); err != nil {
		return nil, err
	}
	if fileCid != "" {
		if err := indexCid(ctx, fileCid, evidenceID); err != nil {
			return nil, err
		}
	}
	return &TxResult{TxID: txID, Timestamp: ts, Version: record.Version}, nil
}

// UpdateEvidence points the record at a new metadata document (B4). It cannot touch the file fields.
func (c *EvidenceContract) UpdateEvidence(ctx contractapi.TransactionContextInterface, evidenceID, expectedVersion,
	newMetadataCid, newMetadataSha256, reason, actorID, actorRole string) (*TxResult, error) {

	actor, err := authorise(ctx, actorID, actorRole, canCreateOrUpdate, "update evidence")
	if err != nil {
		return nil, err
	}
	cur, err := loadEditable(ctx, evidenceID, expectedVersion)
	if err != nil {
		return nil, err
	}
	if err := requireCidAndHash(newMetadataCid, newMetadataSha256, "metadata"); err != nil {
		return nil, err
	}
	if err := requireText(reason, "A reason"); err != nil {
		return nil, err
	}
	if newMetadataCid == cur.MetadataCid {
		return nil, fail(errInvalidArgument, "The metadata CID is unchanged")
	}

	ts, txID := stamp(ctx)
	next := *cur
	next.MetadataCid, next.MetadataSha256 = newMetadataCid, newMetadataSha256
	touch(&next, actor, ts, actionMetadataUpdated, reason)
	if err := commit(ctx, &next, txID, actionMetadataUpdated); err != nil {
		return nil, err
	}
	if err := indexCid(ctx, newMetadataCid, evidenceID); err != nil {
		return nil, err
	}
	return &TxResult{TxID: txID, Timestamp: ts, Version: next.Version}, nil
}

// UpdateStatus performs an ordinary, forward-only status change (D1; the endpoint arrives in Phase 3).
func (c *EvidenceContract) UpdateStatus(ctx contractapi.TransactionContextInterface, evidenceID, expectedVersion,
	newStatus, reason, actorID, actorRole string) (*TxResult, error) {

	actor, err := authorise(ctx, actorID, actorRole, canChangeStatus, "change status")
	if err != nil {
		return nil, err
	}
	cur, err := loadEditable(ctx, evidenceID, expectedVersion)
	if err != nil {
		return nil, err
	}
	if err := requireText(reason, "A reason"); err != nil {
		return nil, err
	}
	if newStatus == "" {
		return nil, fail(errInvalidArgument, "newStatus is required")
	}
	if newStatus == statusDisposed {
		return nil, fail(errInvalidArgument, "DISPOSED is only reachable through an approved disposal")
	}
	if !canMove(cur.Status, newStatus) {
		return nil, fail(errInvalidState, "Status cannot move from %s to %s", cur.Status, newStatus)
	}

	ts, txID := stamp(ctx)
	next := *cur
	next.Status = newStatus
	touch(&next, actor, ts, actionStatusChanged, reason)
	if err := commit(ctx, &next, txID, actionStatusChanged); err != nil {
		return nil, err
	}
	return &TxResult{TxID: txID, Timestamp: ts, Version: next.Version}, nil
}

// RequestDisposal records a disposal request (B5). Nothing is removed.
func (c *EvidenceContract) RequestDisposal(ctx contractapi.TransactionContextInterface, evidenceID, expectedVersion,
	reason, actorID, actorRole string) (*TxResult, error) {

	actor, err := authorise(ctx, actorID, actorRole, canRequestDisposal, "request disposal")
	if err != nil {
		return nil, err
	}
	cur, err := loadEditable(ctx, evidenceID, expectedVersion)
	if err != nil {
		return nil, err
	}
	if err := requireText(reason, "A reason"); err != nil {
		return nil, err
	}
	if cur.Disposal.State == disposalPending {
		return nil, fail(errInvalidState, "A disposal request is already pending")
	}

	ts, txID := stamp(ctx)
	next := *cur
	next.Disposal = Disposal{State: disposalPending, RequestedBy: actor.id, RequestedAt: ts, Reason: reason}
	touch(&next, actor, ts, actionDisposalRequested, reason)
	if err := commit(ctx, &next, txID, actionDisposalRequested); err != nil {
		return nil, err
	}
	return &TxResult{TxID: txID, Timestamp: ts, Version: next.Version}, nil
}

// ApproveDisposal is the only way a record becomes DISPOSED. The approver passes the version they reviewed
// (expectedVersion), so an item edited after the request cannot be approved unseen. The record stays on the
// ledger, readable and verifiable, and is frozen against every further write (C-02, B5).
func (c *EvidenceContract) ApproveDisposal(ctx contractapi.TransactionContextInterface, evidenceID, expectedVersion,
	note, actorID, actorRole string) (*TxResult, error) {

	actor, err := authorise(ctx, actorID, actorRole, canDecideDisposal, "approve disposal")
	if err != nil {
		return nil, err
	}
	cur, err := loadEditable(ctx, evidenceID, expectedVersion)
	if err != nil {
		return nil, err
	}
	if err := requireText(note, "A reason"); err != nil {
		return nil, err
	}
	if cur.Disposal.State != disposalPending {
		return nil, fail(errInvalidState, "There is no pending disposal request")
	}
	if actor.id == cur.Disposal.RequestedBy {
		return nil, fail(errForbiddenRole, "The approver cannot be the person who requested disposal")
	}

	ts, txID := stamp(ctx)
	next := *cur
	next.Status = statusDisposed
	next.Disposal = Disposal{State: disposalNone}
	touch(&next, actor, ts, actionDisposalApproved, note)
	if err := commit(ctx, &next, txID, actionDisposalApproved); err != nil {
		return nil, err
	}
	return &TxResult{TxID: txID, Timestamp: ts, Version: next.Version}, nil
}

// RejectDisposal clears a pending request and leaves the record otherwise unchanged.
func (c *EvidenceContract) RejectDisposal(ctx contractapi.TransactionContextInterface, evidenceID, expectedVersion,
	note, actorID, actorRole string) (*TxResult, error) {

	actor, err := authorise(ctx, actorID, actorRole, canDecideDisposal, "reject disposal")
	if err != nil {
		return nil, err
	}
	cur, err := loadEditable(ctx, evidenceID, expectedVersion)
	if err != nil {
		return nil, err
	}
	if err := requireText(note, "A reason"); err != nil {
		return nil, err
	}
	if cur.Disposal.State != disposalPending {
		return nil, fail(errInvalidState, "There is no pending disposal request")
	}

	ts, txID := stamp(ctx)
	next := *cur
	next.Disposal = Disposal{State: disposalNone}
	touch(&next, actor, ts, actionDisposalRejected, note)
	if err := commit(ctx, &next, txID, actionDisposalRejected); err != nil {
		return nil, err
	}
	return &TxResult{TxID: txID, Timestamp: ts, Version: next.Version}, nil
}

// ------------------------------------------------------------------------------------------------- reads

// GetEvidence returns the current record. Reads carry no role check: only the backend's MSP can reach
// the chaincode at all, and Spring decides who may read what.
func (c *EvidenceContract) GetEvidence(ctx contractapi.TransactionContextInterface, evidenceID string) (*EvidenceRecord, error) {
	return load(ctx, evidenceID)
}

// GetHistory returns every committed version, oldest first (C3), with the transaction id and the ledger
// timestamp of each. Fabric's history is a per-key index maintained by the peer.
func (c *EvidenceContract) GetHistory(ctx contractapi.TransactionContextInterface, evidenceID string) ([]*HistoryEntry, error) {
	if _, err := load(ctx, evidenceID); err != nil { // gives EVIDENCE_NOT_FOUND for an unknown id
		return nil, err
	}
	iter, err := ctx.GetStub().GetHistoryForKey(recordKey(evidenceID))
	if err != nil {
		return nil, fail(errInvalidState, "cannot read history: %v", err)
	}
	defer iter.Close()

	entries := []*HistoryEntry{}
	for iter.HasNext() {
		mod, err := iter.Next()
		if err != nil {
			return nil, fail(errInvalidState, "cannot read history: %v", err)
		}
		if mod.IsDelete { // cannot happen (nothing deletes), skipped defensively
			continue
		}
		var rec EvidenceRecord
		if err := json.Unmarshal(mod.Value, &rec); err != nil {
			return nil, fail(errInvalidState, "corrupt history entry: %v", err)
		}
		normalise(&rec)
		ts := time.Unix(mod.Timestamp.GetSeconds(), int64(mod.Timestamp.GetNanos())).UTC().Format(time.RFC3339Nano)
		entries = append(entries, &HistoryEntry{TxID: mod.TxId, Timestamp: ts, Record: rec})
	}
	// Sorted explicitly by version so correctness does not depend on the iteration order of the peer's index.
	sort.Slice(entries, func(i, j int) bool { return entries[i].Record.Version < entries[j].Record.Version })
	return entries, nil
}

// FindByCid returns the ids of all evidence that ever referenced this CID: its file CID or any metadata CID.
// The same file registered twice gives several ids, so the result is a list. Empty (not an error) if none.
func (c *EvidenceContract) FindByCid(ctx contractapi.TransactionContextInterface, cid string) ([]string, error) {
	if !cidRe.MatchString(cid) {
		return nil, fail(errInvalidArgument, "cid is malformed")
	}
	iter, err := ctx.GetStub().GetStateByPartialCompositeKey(cidIndexType, []string{cid})
	if err != nil {
		return nil, fail(errInvalidState, "cannot read index: %v", err)
	}
	defer iter.Close()

	ids := []string{}
	for iter.HasNext() {
		kv, err := iter.Next()
		if err != nil {
			return nil, fail(errInvalidState, "cannot read index: %v", err)
		}
		_, attrs, err := ctx.GetStub().SplitCompositeKey(kv.Key)
		if err != nil || len(attrs) != 2 {
			return nil, fail(errInvalidState, "corrupt index entry")
		}
		ids = append(ids, attrs[1])
	}
	return ids, nil
}

// ---------------------------------------------------------------------------------------------- helpers

type actorInfo struct{ id, role string }

// authorise is the ONLY place the acting identity is resolved (design section 5; A2_IDENTITY_DESIGN.md section 3.2,
// approved by the owner 2026-09-22). The certificate is now authoritative for WHO is calling and WHAT role they hold
// (constraint C-08, reworded): id and role come from the caller's Fabric CA-issued certificate attributes, read here
// and nowhere else. The actorID/actorRole ARGUMENTS are kept (every function signature is unchanged) and must EQUAL
// the certificate; a mismatch is refused, which is what stops a bug or a compromised Spring layer from claiming a
// different user's identity even while holding that user's connection. There is NO fallback to the argument alone:
// a certificate with no role attribute (this is true of every identity issued before A2, including the old shared
// application identity `User1`) is refused for every write, which is why enrolling every user through the operator
// script BEFORE deploying this chaincode version is a hard prerequisite (docs/FABRIC_RUNBOOK.md section "A2
// enrollment before enforcement"); reads never call authorise, so an unenrolled identity can still read.
func authorise(ctx contractapi.TransactionContextInterface, actorID, actorRole string, allowed map[string]bool, what string) (actorInfo, error) {
	msp, err := ctx.GetClientIdentity().GetMSPID()
	if err != nil || !allowedMSPs[msp] {
		return actorInfo{}, fail(errForbiddenRole, "The invoking organisation is not allowed to write evidence")
	}
	if !userIDRe.MatchString(actorID) || !knownRoles[actorRole] {
		return actorInfo{}, fail(errInvalidArgument, "actor is malformed")
	}
	certRole, hasRole, err := ctx.GetClientIdentity().GetAttributeValue(roleAttr)
	if err != nil {
		return actorInfo{}, fail(errInvalidState, "cannot read certificate attributes: %v", err)
	}
	certID, hasID, err := ctx.GetClientIdentity().GetAttributeValue(enrollmentIDAttr)
	if err != nil {
		return actorInfo{}, fail(errInvalidState, "cannot read certificate attributes: %v", err)
	}
	if !hasRole || !hasID {
		return actorInfo{}, fail(errForbiddenRole, "This identity has no role certificate attribute; only per-user enrolled identities may write evidence")
	}
	if certID != actorID || certRole != actorRole {
		return actorInfo{}, fail(errForbiddenRole, "The supplied actor does not match the calling certificate")
	}
	if !allowed[certRole] {
		return actorInfo{}, fail(errForbiddenRole, "Role %s may not %s", certRole, what)
	}
	return actorInfo{id: certID, role: certRole}, nil
}

// load reads a record, or EVIDENCE_NOT_FOUND.
func load(ctx contractapi.TransactionContextInterface, evidenceID string) (*EvidenceRecord, error) {
	raw, err := ctx.GetStub().GetState(recordKey(evidenceID))
	if err != nil {
		return nil, fail(errInvalidState, "cannot read ledger state: %v", err)
	}
	if raw == nil {
		return nil, fail(errNotFound, "Evidence %s does not exist", evidenceID)
	}
	var rec EvidenceRecord
	if err := json.Unmarshal(raw, &rec); err != nil {
		return nil, fail(errInvalidState, "corrupt record for %s", evidenceID)
	}
	normalise(&rec)
	return &rec, nil
}

// loadEditable applies the checks every update shares, in this order: version argument well formed, item
// exists, item is not DISPOSED, expectedVersion matches. (InMemoryLedgerService uses the same order so the
// same input gives the same error code.)
func loadEditable(ctx contractapi.TransactionContextInterface, evidenceID, expectedVersion string) (*EvidenceRecord, error) {
	want, err := strconv.Atoi(strings.TrimSpace(expectedVersion))
	if err != nil || want < 1 {
		return nil, fail(errInvalidArgument, "expectedVersion must be a positive integer")
	}
	cur, err := load(ctx, evidenceID)
	if err != nil {
		return nil, err
	}
	if cur.Status == statusDisposed {
		return nil, fail(errInvalidState, "Evidence is DISPOSED and can no longer change")
	}
	if cur.Version != want {
		return nil, fail(errVersionConflict, "Expected version %d but the record is at version %d", want, cur.Version)
	}
	return cur, nil
}

// normalise gives records written by an older chaincode version (before custody transfers existed) an explicit
// "NONE" transfer state, so every reader, including the Java side, sees a value it can parse.
func normalise(r *EvidenceRecord) {
	if r.Transfer.State == "" {
		r.Transfer.State = transferNone
	}
}

// touch stamps a modified copy of a record: next version, who, when, what, why.
func touch(r *EvidenceRecord, actor actorInfo, ts, action, reason string) {
	r.Version++
	r.UpdatedBy, r.UpdatedByRole, r.UpdatedAt = actor.id, actor.role, ts
	r.LastAction, r.LastReason = action, reason
}

// commit writes the record and emits the chaincode event (design section 6). Only identifiers are in the payload.
func commit(ctx contractapi.TransactionContextInterface, r *EvidenceRecord, txID, action string) error {
	raw, err := json.Marshal(r)
	if err != nil {
		return fail(errInvalidState, "cannot encode record: %v", err)
	}
	if err := ctx.GetStub().PutState(recordKey(r.EvidenceID), raw); err != nil {
		return fail(errInvalidState, "cannot write ledger state: %v", err)
	}
	payload, _ := json.Marshal(eventPayload{EvidenceID: r.EvidenceID, Version: r.Version, TxID: txID, Action: action})
	if err := ctx.GetStub().SetEvent("Evidence."+eventName(action), payload); err != nil {
		return fail(errInvalidState, "cannot emit event: %v", err)
	}
	return nil
}

// eventName maps an action to the event suffix in the design: Created, MetadataUpdated, StatusChanged, ...
func eventName(action string) string {
	parts := strings.Split(strings.ToLower(action), "_")
	for i, p := range parts {
		parts[i] = strings.ToUpper(p[:1]) + p[1:]
	}
	return strings.Join(parts, "")
}

func indexCid(ctx contractapi.TransactionContextInterface, cid, evidenceID string) error {
	key, err := ctx.GetStub().CreateCompositeKey(cidIndexType, []string{cid, evidenceID})
	if err != nil {
		return fail(errInvalidState, "cannot build index key: %v", err)
	}
	if err := ctx.GetStub().PutState(key, []byte{0x00}); err != nil {
		return fail(errInvalidState, "cannot write index: %v", err)
	}
	return nil
}

// stamp returns the transaction's own timestamp and id (C4). The timestamp is part of the proposal, so every
// endorser derives the same value; time.Now() would differ between them and make the endorsements disagree.
func stamp(ctx contractapi.TransactionContextInterface) (timestamp, txID string) {
	stub := ctx.GetStub()
	ts, err := stub.GetTxTimestamp()
	if err != nil || ts == nil {
		return time.Unix(0, 0).UTC().Format(time.RFC3339Nano), stub.GetTxID()
	}
	return time.Unix(ts.GetSeconds(), int64(ts.GetNanos())).UTC().Format(time.RFC3339Nano), stub.GetTxID()
}

func requireCidAndHash(cid, sha, what string) error {
	if !cidRe.MatchString(cid) {
		return fail(errInvalidArgument, "%s CID is malformed", what)
	}
	if !sha256Re.MatchString(sha) {
		return fail(errInvalidArgument, "%s SHA-256 must be 64 lowercase hex characters", what)
	}
	return nil
}

func requireText(s, what string) error {
	if strings.TrimSpace(s) == "" {
		return fail(errInvalidArgument, "%s is required", what)
	}
	return nil
}

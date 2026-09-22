package main

import (
	"github.com/hyperledger/fabric-contract-api-go/contractapi"
)

// Custody transfer (D2): a two-step handover. The current custodian INITIATES, the named receiver ACCEPTS or
// REJECTS, and the sender may CANCEL while it is pending. Custody changes only on acceptance. This file adds functions
// only: no existing function's behaviour changed (a record gains a transfer field that older records read as NONE).
//
// There is no delete here either. The "pending transfers for a user" lookup is an append-only index, TRF~<user>~<id>,
// written when a transfer is initiated toward that user and never removed; FindPendingTransfers reads each indexed
// record and keeps only those whose transfer is still PENDING toward that user.

const (
	transferNone      = "NONE"
	transferPending   = "PENDING"
	transferAccepted  = "ACCEPTED"
	transferRejected  = "REJECTED"
	transferCancelled = "CANCELLED"

	trfIndexType = "TRF"
	maxNotesLen  = 1000
)

// InitiateTransfer starts a handover to toUserID. Only the CURRENT CUSTODIAN can initiate (this is the custody rule
// itself, not just a role check). toRole is the receiver's role as the backend looked it up (from PostgreSQL, off-chain);
// it must be one that can hold evidence, and the receiver must later accept with that same role, authenticated by their
// OWN certificate (authorise(), A2) - toRole is not itself certificate-backed, since the receiver has not acted yet.
func (c *EvidenceContract) InitiateTransfer(ctx contractapi.TransactionContextInterface, evidenceID, expectedVersion,
	toUserID, toRole, reason, notes, actorID, actorRole string) (*TxResult, error) {

	actor, err := authorise(ctx, actorID, actorRole, canHoldCustody, "transfer custody")
	if err != nil {
		return nil, err
	}
	cur, err := loadEditable(ctx, evidenceID, expectedVersion)
	if err != nil {
		return nil, err
	}
	if actor.id != cur.CurrentCustodian {
		return nil, fail(errForbiddenRole, "Only the current custodian can transfer this evidence")
	}
	if !userIDRe.MatchString(toUserID) {
		return nil, fail(errInvalidArgument, "toUserId is malformed")
	}
	if !canHoldCustody[toRole] {
		return nil, fail(errInvalidArgument, "Role %s cannot hold custody of evidence", toRole)
	}
	if toUserID == actor.id {
		return nil, fail(errInvalidArgument, "Custody cannot be transferred to yourself")
	}
	if err := requireText(reason, "A reason"); err != nil {
		return nil, err
	}
	if len(notes) > maxNotesLen {
		return nil, fail(errInvalidArgument, "notes are longer than %d characters", maxNotesLen)
	}
	if cur.Transfer.State == transferPending {
		return nil, fail(errInvalidState, "A transfer is already pending")
	}
	if cur.Disposal.State == disposalPending {
		return nil, fail(errInvalidState, "Evidence cannot be transferred while a disposal request is pending")
	}

	ts, txID := stamp(ctx)
	next := *cur
	next.Transfer = Transfer{State: transferPending, From: actor.id, To: toUserID, ToRole: toRole, Reason: reason,
		Notes: notes, InitiatedAt: ts}
	touch(&next, actor, ts, actionTransferInitiated, reason)
	if err := commit(ctx, &next, txID, actionTransferInitiated); err != nil {
		return nil, err
	}
	key, err := ctx.GetStub().CreateCompositeKey(trfIndexType, []string{toUserID, evidenceID})
	if err != nil {
		return nil, fail(errInvalidState, "cannot build index key: %v", err)
	}
	if err := ctx.GetStub().PutState(key, []byte{0x00}); err != nil {
		return nil, fail(errInvalidState, "cannot write index: %v", err)
	}
	return &TxResult{TxID: txID, Timestamp: ts, Version: next.Version}, nil
}

// AcceptTransfer completes the handover: the named receiver becomes the current custodian.
func (c *EvidenceContract) AcceptTransfer(ctx contractapi.TransactionContextInterface, evidenceID, expectedVersion,
	note, actorID, actorRole string) (*TxResult, error) {
	return resolveTransfer(ctx, evidenceID, expectedVersion, note, actorID, actorRole, transferAccepted)
}

// RejectTransfer declines the handover: custody stays with the sender.
func (c *EvidenceContract) RejectTransfer(ctx contractapi.TransactionContextInterface, evidenceID, expectedVersion,
	note, actorID, actorRole string) (*TxResult, error) {
	return resolveTransfer(ctx, evidenceID, expectedVersion, note, actorID, actorRole, transferRejected)
}

// CancelTransfer withdraws a pending handover. Only the sender can.
func (c *EvidenceContract) CancelTransfer(ctx contractapi.TransactionContextInterface, evidenceID, expectedVersion,
	note, actorID, actorRole string) (*TxResult, error) {
	return resolveTransfer(ctx, evidenceID, expectedVersion, note, actorID, actorRole, transferCancelled)
}

func resolveTransfer(ctx contractapi.TransactionContextInterface, evidenceID, expectedVersion, note, actorID, actorRole,
	outcome string) (*TxResult, error) {

	actor, err := authorise(ctx, actorID, actorRole, canHoldCustody, "resolve a custody transfer")
	if err != nil {
		return nil, err
	}
	cur, err := loadEditable(ctx, evidenceID, expectedVersion)
	if err != nil {
		return nil, err
	}
	if cur.Transfer.State != transferPending {
		return nil, fail(errInvalidState, "There is no pending transfer")
	}
	if len(note) > maxNotesLen {
		return nil, fail(errInvalidArgument, "note is longer than %d characters", maxNotesLen)
	}
	if outcome == transferCancelled {
		if actor.id != cur.Transfer.From {
			return nil, fail(errForbiddenRole, "Only the sender can cancel a transfer")
		}
	} else {
		if actor.id != cur.Transfer.To {
			return nil, fail(errForbiddenRole, "Only the named receiver can respond to this transfer")
		}
		if actor.role != cur.Transfer.ToRole {
			return nil, fail(errForbiddenRole, "The responding role does not match the role the transfer was addressed to")
		}
	}

	ts, txID := stamp(ctx)
	next := *cur
	next.Transfer.State, next.Transfer.ResolvedAt, next.Transfer.ResolutionNote = outcome, ts, note
	action := actionTransferRejected
	switch outcome {
	case transferAccepted:
		next.CurrentCustodian = cur.Transfer.To
		action = actionTransferAccepted
	case transferCancelled:
		action = actionTransferCancelled
	}
	touch(&next, actor, ts, action, cur.Transfer.Reason)
	if err := commit(ctx, &next, txID, action); err != nil {
		return nil, err
	}
	return &TxResult{TxID: txID, Timestamp: ts, Version: next.Version}, nil
}

// FindPendingTransfers returns the ids of evidence with a transfer PENDING toward userID (D2: "pending transfers are
// listed for the receiver"). Empty, never null, when there are none.
func (c *EvidenceContract) FindPendingTransfers(ctx contractapi.TransactionContextInterface, userID string) ([]string, error) {
	if !userIDRe.MatchString(userID) {
		return nil, fail(errInvalidArgument, "userId is malformed")
	}
	iter, err := ctx.GetStub().GetStateByPartialCompositeKey(trfIndexType, []string{userID})
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
		rec, err := load(ctx, attrs[1])
		if err != nil {
			return nil, err
		}
		if rec.Transfer.State == transferPending && rec.Transfer.To == userID {
			ids = append(ids, attrs[1])
		}
	}
	return ids, nil
}

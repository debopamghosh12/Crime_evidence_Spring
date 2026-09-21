package main

// Types stored on, and returned from, the ledger. Field names are a CONTRACT with the Java side
// (FabricLedgerService parses them into LedgerEvidenceRecord / LedgerTxResult / LedgerHistoryEntry):
// renaming a json tag here breaks the backend, and a test pins the exact set of keys.
//
// Nothing personal is stored (constraint C-06): opaque user ids, hashes, CIDs, roles and timestamps only.
// Every field tagged omitempty is ALSO tagged metadata:",optional": the contract framework validates each
// returned value against a schema in which all struct fields are required unless marked optional, so without the
// tag a physical item (no file) or an item with no pending disposal fails on every read. Only a real peer
// exposes this (found on the first live query); TestOmitemptyFieldsAreAlsoOptionalInTheSchema guards it.
// Timestamps are strings in RFC 3339 with nanoseconds, taken from the transaction (see now()); strings
// rather than time.Time keep the generated contract metadata simple and the encoding explicit.

// Disposal is the state of a disposal request (B5). State is "NONE" or "PENDING"; the other fields are
// only meaningful while PENDING.
type Disposal struct {
	State       string `json:"state"`
	RequestedBy string `json:"requestedBy,omitempty" metadata:",optional"`
	RequestedAt string `json:"requestedAt,omitempty" metadata:",optional"`
	Reason      string `json:"reason,omitempty" metadata:",optional"`
}

// EvidenceRecord is the world-state value stored under EV~<evidenceId>.
type EvidenceRecord struct {
	DocType        string `json:"docType"`
	EvidenceID     string `json:"evidenceId"`
	CaseID         string `json:"caseId"`
	EvidenceType   string `json:"evidenceType"`
	Status         string `json:"status"`
	Version        int    `json:"version"`
	MetadataCid    string `json:"metadataCid"`
	MetadataSha256 string `json:"metadataSha256"`
	// File fields: DIGITAL only, and IMMUTABLE after creation (no function assigns them again).
	FileCid          string   `json:"fileCid,omitempty" metadata:",optional"`
	FileSha256       string   `json:"fileSha256,omitempty" metadata:",optional"`
	FileSize         int64    `json:"fileSize,omitempty" metadata:",optional"`
	CreatedBy        string   `json:"createdBy"`
	CreatedByRole    string   `json:"createdByRole"`
	CreatedAt        string   `json:"createdAt"`
	UpdatedBy        string   `json:"updatedBy"`
	UpdatedByRole    string   `json:"updatedByRole"`
	UpdatedAt        string   `json:"updatedAt"`
	LastAction       string   `json:"lastAction"`
	LastReason       string   `json:"lastReason"`
	CurrentCustodian string   `json:"currentCustodian"`
	Disposal         Disposal `json:"disposal"`
}

// TxResult is returned by every write so the caller learns the transaction id and the LEDGER's
// timestamp (C4) without a second query.
type TxResult struct {
	TxID      string `json:"txId"`
	Timestamp string `json:"timestamp"`
	Version   int    `json:"version"`
}

// HistoryEntry is one committed version of a record (C3).
type HistoryEntry struct {
	TxID      string         `json:"txId"`
	Timestamp string         `json:"timestamp"`
	Record    EvidenceRecord `json:"record"`
}

// eventPayload is the body of every chaincode event (design section 6): identifiers only.
type eventPayload struct {
	EvidenceID string `json:"evidenceId"`
	Version    int    `json:"version"`
	TxID       string `json:"txId"`
	Action     string `json:"action"`
}

// Actions, one per write function. They are the record's lastAction and the suffix of the event name.
const (
	actionCreated           = "CREATED"
	actionMetadataUpdated   = "METADATA_UPDATED"
	actionStatusChanged     = "STATUS_CHANGED"
	actionDisposalRequested = "DISPOSAL_REQUESTED"
	actionDisposalApproved  = "DISPOSAL_APPROVED"
	actionDisposalRejected  = "DISPOSAL_REJECTED"
)

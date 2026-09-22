package main

import (
	"fmt"
	"regexp"
)

// Error codes. The chaincode returns errors as "CODE: message"; the Spring side (FabricLedgerService)
// maps CODE to its LedgerErrorCode enum, so these strings are part of the contract (design section 4).
const (
	errNotFound        = "EVIDENCE_NOT_FOUND"
	errExists          = "EVIDENCE_EXISTS"
	errForbiddenRole   = "FORBIDDEN_ROLE"
	errInvalidArgument = "INVALID_ARGUMENT"
	errInvalidState    = "INVALID_STATE"
	errVersionConflict = "VERSION_CONFLICT"
)

func fail(code, format string, args ...any) error {
	return fmt.Errorf("%s: %s", code, fmt.Sprintf(format, args...))
}

// Roles and statuses. The role names equal the Java Role enum, which is also what the users.role column stores.
const (
	roleCollector       = "COLLECTOR"
	roleForensicAnalyst = "FORENSIC_ANALYST"
	roleProsecutor      = "PROSECUTOR"
	roleJudge           = "JUDGE"
	roleAuditor         = "AUDITOR"
	roleAdmin           = "ADMIN"

	statusCollected  = "COLLECTED"
	statusProcessing = "PROCESSING"
	statusAnalyzed   = "ANALYZED"
	statusArchived   = "ARCHIVED"
	statusReleased   = "RELEASED"
	statusDisposed   = "DISPOSED"

	typeDigital  = "DIGITAL"
	typePhysical = "PHYSICAL"

	disposalNone    = "NONE"
	disposalPending = "PENDING"
)

var knownRoles = map[string]bool{
	roleCollector: true, roleForensicAnalyst: true, roleProsecutor: true,
	roleJudge: true, roleAuditor: true, roleAdmin: true,
}

// The role table from CHAINCODE_DESIGN.md section 4 (owner-approved as G3). It exists in three places that
// must agree: this file, Spring's Permissions/@PreAuthorize, and InMemoryLedgerService.CAN_*.
// ADMIN and AUDITOR are in none of these sets: they can read but never write (A4).
var (
	canCreateOrUpdate  = set(roleCollector, roleForensicAnalyst)
	canChangeStatus    = set(roleCollector, roleForensicAnalyst, roleProsecutor)
	canRequestDisposal = set(roleCollector, roleProsecutor)
	canDecideDisposal  = set(roleJudge)
	// Roles that can be a custodian: hold the item, hand it on, or receive it (D2). AUDITOR and ADMIN never hold evidence.
	canHoldCustody = set(roleCollector, roleForensicAnalyst, roleProsecutor, roleJudge)
)

func set(items ...string) map[string]bool {
	m := make(map[string]bool, len(items))
	for _, i := range items {
		m[i] = true
	}
	return m
}

// allowedMSPs are the organisations whose identities may invoke this chaincode: the backend's org(s).
var allowedMSPs = set("Org1MSP", "Org2MSP")

// A2: certificate attribute names authorise() reads (docs/A2_IDENTITY_DESIGN.md section 3.1). roleAttr is set at
// registration (`--id.attrs 'role=<ROLE>:ecert'`); enrollmentIDAttr must be explicitly requested at enrollment
// (`--enrollment.attrs 'role,hf.EnrollmentID'`) or it is silently absent from the certificate (proved by the spike,
// TEST_CHECKLIST Appendix P3-A).
const (
	roleAttr         = "role"
	enrollmentIDAttr = "hf.EnrollmentID"
)

// Status transitions (PROVISIONAL, finalised with D1 in Phase 3). DISPOSED is deliberately absent as a
// target: it is only entered through ApproveDisposal, never through UpdateStatus.
var statusTransitions = map[string]string{
	statusCollected:  statusProcessing,
	statusProcessing: statusAnalyzed,
	statusAnalyzed:   statusArchived,
	statusArchived:   statusReleased,
}

func canMove(from, to string) bool { return statusTransitions[from] == to && to != "" }

// Input formats. The same patterns are enforced by the Java side (InMemoryLedgerService, Cid) so that
// a value accepted by one is accepted by the other.
var (
	evidenceIDRe = regexp.MustCompile(`^EV-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$`)
	caseIDRe     = regexp.MustCompile(`^[A-Za-z0-9._/-]{1,64}$`)
	sha256Re     = regexp.MustCompile(`^[0-9a-f]{64}$`)
	userIDRe     = regexp.MustCompile(`^[0-9a-fA-F-]{36}$`)
	cidRe        = regexp.MustCompile(`^(Qm[1-9A-HJ-NP-Za-km-z]{44}|b[a-z2-7]{50,120})$`)
)

package main

import (
	"strings"
	"testing"

	"github.com/hyperledger/fabric-contract-api-go/contractapi"
)

// A2: per-user Fabric identity (docs/A2_IDENTITY_DESIGN.md section 3.2, section 6 verification plan item 1).
// authorise() is exercised through every existing test via invokeAs (fake_ledger_test.go), which presents a
// certificate matching the chaincode arguments - proving the ROLE-PERMISSION logic is unchanged. These tests are
// about the NEW check: whether the certificate itself is trusted, independent of role permissions.

// A certificate with no role/hf.EnrollmentID attribute at all - this is every identity enrolled before A2,
// including the old shared application identity `User1` - may read but is refused for every write. No fallback
// to the supplied argument: this is the entire point of A2 (closes C-08 for the write path).
func TestACertificateWithNoRoleAttributeIsRefusedForEveryWriteButReadsStillWork(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t) // created with a matching certificate (invokeAs), so this exists to read

	bareIdentityCalls := map[string]func() error{
		"create": func() error {
			return l.invoke(org1, func(ctx contractapi.TransactionContextInterface) error {
				_, e := c.CreateEvidence(ctx, newID(), "C", typePhysical, meta1, sha, "", "", "", collectorID, roleCollector)
				return e
			})
		},
		"update": func() error {
			return l.invoke(org1, func(ctx contractapi.TransactionContextInterface) error {
				_, e := c.UpdateEvidence(ctx, id, "1", meta2, sha, "r", collectorID, roleCollector)
				return e
			})
		},
		"status": func() error {
			return l.invoke(org1, func(ctx contractapi.TransactionContextInterface) error {
				_, e := c.UpdateStatus(ctx, id, "1", statusProcessing, "r", collectorID, roleCollector)
				return e
			})
		},
		"initiateTransfer": func() error {
			return l.invoke(org1, func(ctx contractapi.TransactionContextInterface) error {
				_, e := c.InitiateTransfer(ctx, id, "1", analystID, roleForensicAnalyst, "r", "", collectorID, roleCollector)
				return e
			})
		},
	}
	for name, call := range bareIdentityCalls {
		if err := call(); err == nil || !strings.HasPrefix(err.Error(), errForbiddenRole+":") {
			t.Errorf("%s: expected FORBIDDEN_ROLE (no cert), got %v", name, err)
		}
	}

	// Reads never call authorise, so the same bare identity (no cert attributes) reads fine.
	if _, err := l.get(id); err != nil {
		t.Fatalf("read with no certificate should still work: %v", err)
	}
	if _, err := l.hist(id); err != nil {
		t.Fatalf("history read with no certificate should still work: %v", err)
	}
}

// A certificate WITH attributes that do not match the actorID/actorRole the caller supplied as arguments is
// refused, even though both individually look like valid role/id values. This is the defence against a bug (or a
// compromised backend) supplying the wrong user's id or role while holding a different, legitimately enrolled
// user's connection.
func TestArgumentAndCertificateMismatchIsRefused(t *testing.T) {
	l := newFakeLedger()
	id := l.createDigital(t)

	mismatches := map[string]func() error{
		"cert id, wrong argument role": func() error {
			return l.invokeAs(org1, collectorID, roleCollector, func(ctx contractapi.TransactionContextInterface) error {
				_, e := c.UpdateStatus(ctx, id, "1", statusProcessing, "r", collectorID, roleJudge)
				return e
			})
		},
		"cert role, wrong argument id": func() error {
			return l.invokeAs(org1, collectorID, roleCollector, func(ctx contractapi.TransactionContextInterface) error {
				_, e := c.UpdateStatus(ctx, id, "1", statusProcessing, "r", analystID, roleCollector)
				return e
			})
		},
		"a JUDGE certificate claiming COLLECTOR in the argument": func() error {
			return l.invokeAs(org1, judgeID, roleJudge, func(ctx contractapi.TransactionContextInterface) error {
				_, e := c.UpdateEvidence(ctx, id, "1", meta2, sha, "r", collectorID, roleCollector)
				return e
			})
		},
		"a COLLECTOR certificate claiming JUDGE in the argument": func() error {
			return l.invokeAs(org1, collectorID, roleCollector, func(ctx contractapi.TransactionContextInterface) error {
				_, e := c.ApproveDisposal(ctx, id, "1", "ok", judgeID, roleJudge)
				return e
			})
		},
	}
	for name, call := range mismatches {
		if err := call(); err == nil || !strings.HasPrefix(err.Error(), errForbiddenRole+":") {
			t.Errorf("%s: expected FORBIDDEN_ROLE (mismatch), got %v", name, err)
		}
	}
	// The record is exactly as created: none of the forged calls above wrote anything.
	if r := l.mustGet(t, id); r.Version != 1 {
		t.Fatalf("a mismatched call modified the ledger: %+v", r)
	}
}

// A certificate that matches the argument but whose role has no permission for the function is still refused,
// same as before A2 - this proves the role-PERMISSION table (canCreateOrUpdate etc.) still applies on top of the
// certificate check, not instead of it.
func TestACertificateThatMatchesButLacksPermissionIsStillRefused(t *testing.T) {
	l := newFakeLedger()
	err := l.invokeAs(org1, auditorID, roleAuditor, func(ctx contractapi.TransactionContextInterface) error {
		_, e := c.CreateEvidence(ctx, newID(), "C", typePhysical, meta1, sha, "", "", "", auditorID, roleAuditor)
		return e
	})
	wantCode(t, err, errForbiddenRole)
}

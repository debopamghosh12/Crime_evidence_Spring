# A2: Per-user Fabric identity: design for owner approval

> **Status: DRAFT FOR APPROVAL. Nothing described here is implemented, and nothing already built in Phase 2 has been changed.**
> A2 changes how every evidence write is authenticated, so per the owner's instruction this is shown before any code is written and
> before anything from Phase 2 is touched. Section 8 lists the exact things that would change and the questions I need answered.
> Related: CONSTRAINTS.md C-08, CHAINCODE_DESIGN.md sections 5 and 10, FEATURE_LIST.md A2.

## 1. The problem, precisely

Today (Phase 2, as built and verified): the Spring backend signs **every** transaction with ONE Fabric identity (`User1@org1`). It passes
`actorId` and `actorRole` to the chaincode as plain arguments. The chaincode's `authorise()` checks the role against the ACL table, but the
role is whatever the caller said. Result (C-08): a bug in Spring, or a compromised backend, can claim any role and the ledger cannot tell.

## 2. What the spike on the real CA proved (2026-09-22, Fabric CA v1.5.17, `ca-org1`; Appendix A)

| Question | Result on the real CA |
|---|---|
| Can a user certificate carry a role that the chaincode can read? | Yes. Registering with `--id.attrs 'role=JUDGE:ecert'` puts `{"attrs":{"role":"JUDGE"}}` into the certificate (OID 1.2.3.4.5.6.7.8.1). The `:ecert` flag is required. |
| Does the certificate identify the user? | Subject `CN=<enrollment id>`. `hf.EnrollmentID` is in the certificate only if **requested at enrollment** (`--enrollment.attrs 'role,hf.EnrollmentID'` gave `{"attrs":{"hf.EnrollmentID":"<user-uuid>","role":"JUDGE"}}`); asking for `role` alone dropped it. |
| Can the backend's registrar be limited? | Yes. A registrar registered with `hf.Registrar.Roles=client, hf.Registrar.Attributes=role, hf.Revoker=true` registered a normal user fine, and **all 5 escalation attempts were refused** (`Error Code: 71 - Authorization failure`): an extra attribute (`admin=true`), identity type `admin`, type `peer`, a registrar attribute, and the root affiliation. The default bootstrap registrar has `*` on everything and must not be used by the app. |
| Are secrets one-time? | Yes: `--id.maxenrollments 1`; a second enrollment with the same secret was refused (`Error Code: 20 - Authentication failure`). |
| Certificate lifetime? | `8760h` (1 year) by default. |
| Key file permissions from the client? | `0600`. |
| Revocation? | The CA records it (`Successfully revoked certificates: [...]`) and can generate a CRL (`gencrl`), **but peers only honour a CRL that is part of the channel's MSP configuration**, which is a channel config update and is not part of this design. |

Also found: `revoke --reason` is not a valid flag in this client; and my first cleanup missed UUID-named users (both were revoked afterwards;
listed in ROLLBACK.md). No original identity was touched.

## 3. Design

### 3.1 Identity model
- **One Fabric identity per application user.** Enrollment ID = `users.id` (the UUID that is already the JWT `sub`), type `client`,
  affiliation `org1.department1`, attributes `role=<ROLE>:ecert`, enrolled with `--enrollment.attrs 'role,hf.EnrollmentID'`.
- The certificate is therefore the ledger-level proof of both *who* (CN / `hf.EnrollmentID`) and *what role*, issued by `ca-org1`, whose
  root the peers already trust (the MSP validates it; no chaincode configuration needed).

### 3.2 Chaincode (new version, same function signatures)
`authorise()` becomes the only place that changes (it was written as `resolveActor` for this):
1. Read `role` and `hf.EnrollmentID` from the caller's certificate (`GetAttributeValue`). **The certificate is authoritative.**
2. The `actorId`/`actorRole` **arguments are kept** and must **equal** the certificate values; any mismatch is `FORBIDDEN_ROLE`
   ("identity does not match certificate"). This defends against Spring picking the wrong user's key (a bug), and keeps every function
   signature, the Java `LedgerService` interface and all Phase 2 controllers/services unchanged.
3. **Strict, no fallback:** a certificate without a `role` attribute (including today's `User1`) may **read** but is refused for every write.
   A fallback to the argument would keep the C-08 hole open, so there is none. (Consequence: a hard cutover; see 5.)
4. MSP allow-list (Org1MSP/Org2MSP) and the role table stay as they are.

### 3.3 Backend
- `ledger/IdentityStore` (interface) with `FileWalletIdentityStore`: wallet directory from `FABRIC_WALLET_DIR` (outside the repo, C-07), one
  sub-directory per user id holding `cert.pem` and `key.pem`. Keys may be PKCS#8-encrypted; the passphrase comes from `FABRIC_WALLET_PASSPHRASE`.
- `FabricLedgerService` keeps **one shared gRPC channel** and a small bounded cache of `Gateway` objects, one per user; a write uses
  the actor's gateway, so the transaction is signed with that user's key. Reads keep using the service identity (no role needed).
- A user with no wallet entry gets `403 LEDGER_IDENTITY_MISSING` ("your account has no ledger identity"), never a silent fallback.
- **Unchanged:** `LedgerService`, `LedgerActor`, `EvidenceService`, `EvidenceController`, all DTOs, the JWT flow, roles, `Permissions`.
  The actor is still built from the JWT (C-05).

### 3.4 Enrollment (three options)
| Option | What | Verdict |
|---|---|---|
| **1. Operator script (recommended now)** | `scripts/fabric/enroll_users.sh`: reads user ids and roles from Postgres, registers each through the least-privilege registrar `be-registrar`, enrolls, writes the wallet with `0600`. Run at provisioning. **The running backend never holds any CA credential.** | Fits Phase 3: users exist only via the dev seeder (A4 unscheduled). Least standing privilege. |
| 2. In-app enrollment when a user is created | Backend calls the CA REST API (CSR via BouncyCastle, registrar token auth). | Needed only once A4 (admin creates users) exists. The backend would then hold registrar credentials (limited, per the spike). `IdentityStore` leaves room for it. Not built now. |
| 3. User-held keys, client-side signing | Users sign in a wallet/app; backend only relays. | The only option that truly stops a compromised backend, but it needs a client application and a different API. **Future work**, described, not claimed. |

The `be-registrar` identity is registered once by the CA administrator (a one-time operator step; its secret lives in the operator's shell, not
in the repo or the app).

### 3.5 Lifecycle
- **Expiry:** certificates last 1 year. The store reads `notAfter`; health warns under 30 days; renewal = run the script again (new key, old
  certificate revoked).
- **Role change:** certificate attributes are immutable, so a role change = revoke + re-enroll with the new attribute (when A4 exists).
- **Deactivation (A4):** delete the wallet entry (immediate, backend-enforced) and revoke at the CA. **Peers will not reject the revoked
  certificate** without a CRL in the channel MSP config (see residual risk 4).

## 4. What A2 closes, and what it does NOT (please read; this corrects the framing "finally closes C-08")

| | |
|---|---|
| **Closes** | (a) The chaincode no longer trusts a caller-supplied role: role and identity come from a CA-signed certificate. (b) A Spring bug or injected value that supplies the wrong actor is rejected on the ledger. (c) Every transaction is signed by the acting user; the ledger's own creator record names that user, independent of any argument (provable with `qscc GetTransactionByID`). (d) The old shared application identity can no longer write. |
| **Does NOT close** | 1. **The backend still custodies every user's private key**, so a compromised backend host can sign as any enrolled user. This is the honest residual of C-08. Only option 3 removes it. 2. Registrar credentials exist (offline, least-privilege); their loss lets an attacker enroll new `role=JUDGE` users. 3. Role/attribute changes need re-enrollment. 4. No peer-side revocation (no channel CRL). 5. Expiry and renewal are operational, not automatic. |

**Recommendation:** on approval, replace C-08 by a revised C-08 ("the chaincode authenticates user and role from a CA-issued certificate;
the backend custodies user keys; a compromised backend can still act as any enrolled user") instead of declaring it closed. I will not edit
C-08 until you approve this wording.

## 5. Impact on what is already built (needs your confirmation, per your instruction)

| Item | Changes? |
|---|---|
| B1-B5 endpoints, `EvidenceService`, DTOs, `Permissions`, JWT/auth flow, `LedgerService` interface | **No code change.** |
| **How every evidence write is authenticated on the ledger** | **YES.** From the signer (one shared identity -> the user's own) to what the chaincode believes (argument -> certificate). |
| `FabricLedgerService` (signer selection, wallet), `FabricProperties` (+ wallet vars), chaincode `authorise()` | Yes (internal to the ledger layer). |
| Users without a wallet entry | Their writes now fail with 403; before they succeeded. Reads unaffected. |
| Hard cutover | The chaincode version that enforces this must be deployed AFTER all dev users are enrolled, or evidence writes stop working in between. |
| Existing ledger data | Untouched. Records written under the old shared identity keep their argument-supplied actor ids; only new writes carry certificate-proven identity (history mixes both; recorded in the write-up). |
| Test suites | Existing Java tests keep passing (interface unchanged); new Go and Java tests added; the whole Phase 2 checklist is re-run through per-user identities. |

## 6. Verification plan (what "done" will mean)
1. Go tests with a fake identity that has attributes: role from certificate; missing `role` attribute refused for writes but reads allowed;
   argument/certificate mismatch (both id and role) refused; unknown MSP refused.
2. Real chaincode driven through the `peer` CLI **with different real identities**: `User1` (no role attribute) -> every write refused;
   a JUDGE certificate claiming COLLECTOR in the argument -> refused; a COLLECTOR certificate claiming JUDGE -> refused. This is the direct
   demonstration that role forgery by the backend's own caller no longer works.
3. Java: wallet loading (plain and encrypted key), cache bound, missing entry -> 403, expiry read, no key material in logs or error bodies.
4. Live: enroll the six dev users through the script, re-run `scripts/live/live_phase2.sh` unchanged (must still match), then
   `qscc GetTransactionByID` shows the creator certificate CN equals the user's UUID.
5. Recorded as gaps, not tested: compromised-backend scenario (by definition unsolved), peer-side revocation, certificate expiry rollover.

## 7. Alternatives rejected
- **One shared identity per role (6 total):** cheap, closes role forgery, but loses per-user attribution on the ledger and still needs the actor id as an argument.
- **Role in an OU / NodeOUs:** NodeOUs only distinguish client/peer/admin/orderer; a custom OU set at enrollment cannot be restricted the way attributes are.
- **Chaincode verifying a Spring-signed assertion (JWT):** would put a shared secret into the chaincode; worse than the certificate.
- **Idemix / anonymous credentials, HSM/PKCS#11:** out of scope for this project.
- **`fabric-sdk-java` for CA calls:** deprecated and drags a conflicting protobuf/gRPC stack next to `fabric-gateway`.

## 8. Decisions I need from you

| # | Question | My default |
|---|---|---|
| A2-Q1 | Confirm that changing the **ledger-side authentication of B1-B5 writes** (section 5) is approved, with B1-B5 Java code untouched | **Yes** |
| A2-Q2 | Enrollment: Option 1 (operator script) now, Option 2 with A4; Option 3 recorded as future work | **Yes** |
| A2-Q3 | Chaincode strict, no fallback for certificates without a `role` attribute (hard cutover after enrolling users) | **Yes** |
| A2-Q4 | Keep the `actorId`/`actorRole` arguments and require them to match the certificate | **Yes** |
| A2-Q5 | Wallet keys encrypted with a passphrase from `FABRIC_WALLET_PASSPHRASE` | **Yes** |
| A2-Q6 | Registrar: least-privilege `be-registrar`, secret held by the operator only, never by the running backend | **Yes** |
| A2-Q7 | Reword C-08 as in section 4 rather than mark it closed | **Yes** |
| A2-Q8 | No new Maven dependency (BouncyCastle for PKCS#8 decryption is already on the classpath through `fabric-gateway`; I would declare it explicitly, which is a C-04 item) | **Approve declaring `bcpkix-jdk18on`** |

## Appendix A: spike log (verbatim, identities redacted where noted)
See `docs/TEST_CHECKLIST.md` Appendix P3-A for the full command output; the decisive lines are quoted in section 2 above.

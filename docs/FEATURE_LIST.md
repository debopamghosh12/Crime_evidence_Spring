# BlockEvidence: Backend Feature List

> Source: `BlockEvidence_Backend_Feature_List.pdf` (8 pages), converted to Markdown so it can be
> referenced every session. Table content was extracted programmatically from the PDF, not retyped.
> Feature IDs (A1, B5, K1 ...) are what CLAUDE.md, FLOW.md and DECISIONS.md refer to.

Spring Boot rewrite of the Crime Evidence Management backend.

This is the complete list of features the backend needs, grouped by area. It combines what the Node.js
prototype already does with what is missing for a system that can actually be trusted as evidence storage.
Each feature has a priority and a note on whether it already exists in the prototype.

## How to read this list

| Label | Meaning |
|---|---|
| P0 - Must have (24) | Needed for the tamper-proof claim to be true. Build these first. |
| P1 - Should have (22) | Makes the system credible and complete for a final-year project. |
| P2 - Stretch (12) | Nice to have if time allows. Good for the viva and future work. |
| Exists | Already in the Node.js prototype, so it is a straight port. |
| Change | Exists in the prototype but the design must change (for example delete becomes archive). |
| New | Not in the prototype. |

Total features: 58 across 12 areas.

## Decision to make before coding

The report describes Polygon, Chainlink VRF, a DAO Jury and Web3Auth, while the working prototype runs on
Hyperledger Fabric (channel `crimechannel`, chaincode `basic`). This list targets Fabric with the
`fabric-gateway` Java client. Everything sits behind a `LedgerService` interface, so a Polygon
implementation with web3j can be added later without changing the controllers.


## A. Identity, authentication and access control

| ID | Feature | What it does | Spring Boot / tech | Pri. | Prototype |
|---|---|---|---|---|---|
| A1 | Login with JWT | Email/password login that issues a short-lived access token plus a refresh token. Passwords stored with BCrypt. | Spring Security, jjwt, BCryptPasswordEncoder | P0 | New |
| A2 | Per-user ledger identity | Every user is mapped to their own Fabric identity (enrolled through Fabric CA). The officer recorded on the ledger comes from the token, never from the request body. | fabric-gateway, Fabric CA client, wallet/key store | P0 | Change |
| A3 | Role-based access control | Roles: Collector, Forensic Analyst, Prosecutor, Judge, Auditor, Admin. Checked in Spring and checked again inside the chaincode. | @PreAuthorize, method security, chaincode ACL | P0 | New |
| A4 | Admin user management | Create and deactivate users, assign roles and department. Admin cannot edit or delete evidence. | Spring Data JPA, UserController | P1 | New |
| A5 | Case-level access | Users only see cases and evidence they are assigned to (Auditor and Judge get read-only access). | Custom permission evaluator | P1 | New |
| A6 | Access audit log | Log every view, download and failed attempt with user, resource, time and IP. | Spring AOP / filter, audit table | P1 | New |

## B. Evidence management

| ID | Feature | What it does | Spring Boot / tech | Pri. | Prototype |
|---|---|---|---|---|---|
| B1 | Register evidence | Create an evidence record with type (physical/digital), description, where it was found, collector and case. The server generates the ID. Metadata goes to IPFS, the CID goes to the ledger. | EvidenceController, EvidenceService, LedgerService | P0 | Change |
| B2 | File upload | Upload the digital evidence file (multipart) with size and type limits, then pin it to IPFS. | MultipartFile, IPFS HTTP API via RestClient | P0 | New |
| B3 | Retrieve evidence | Get by evidence ID or by CID, returning metadata, ledger info and current verification status. | GET /api/evidence/{id} | P0 | Exists |
| B4 | Versioned metadata update | An update never overwrites. Each change creates a new version with a mandatory reason, and older versions stay readable. | Chaincode UpdateAsset, version field | P0 | Change |
| B5 | Archive / dispose instead of delete | Disposal needs a reason and approval from an authorised role. The record stays on the ledger. Replaces the DELETE and bulk DELETE endpoints. | Approval workflow, status DISPOSED | P0 | Change |
| B6 | Bulk register | Batch create that returns a per-item result (partial success is allowed). | POST /api/evidence/bulk | P2 | Exists |
| B7 | Physical evidence tracking | Evidence tag or barcode number, storage room/locker, condition notes. | Extra entity fields | P1 | New |
| B8 | Tags and related evidence | Tag evidence and link related items within a case. | JPA many-to-many | P2 | New |

## C. Integrity and verification

| ID | Feature | What it does | Spring Boot / tech | Pri. | Prototype |
|---|---|---|---|---|---|
| C1 | SHA-256 file hash | Compute the hash of the file at upload and store it on the ledger next to the CID. | MessageDigest (SHA-256), streaming | P0 | New |
| C2 | Verify endpoint | GET /api/evidence/{id}/verify re-fetches the file, re-hashes it and compares with the ledger. Returns VERIFIED, TAMPERED or NOT_FOUND. | VerificationService | P0 | New |
| C3 | Ledger history | Full version history with transaction IDs and timestamps. | Fabric GetHistoryForKey | P0 | New |
| C4 | Ledger-side timestamps | Use the transaction timestamp from the chaincode instead of the server clock. | Chaincode stub.GetTxTimestamp | P1 | Change |
| C5 | Scheduled integrity sweep | A periodic job re-verifies all evidence and raises an alert on any mismatch. | @Scheduled, notification service | P2 | New |

## D. Status and chain of custody

| ID | Feature | What it does | Spring Boot / tech | Pri. | Prototype |
|---|---|---|---|---|---|
| D1 | Status state machine | COLLECTED, PROCESSING, ANALYZED, ARCHIVED, RELEASED. Invalid jumps (for example COLLECTED to RELEASED) are rejected in the service and in the chaincode. | Enum with allowed-transition map | P0 | Change |
| D2 | Two-step custody transfer | Sender initiates a transfer, receiver accepts or rejects it. Pending transfers are listed for the receiver. | Custody entity, accept/reject endpoints | P0 | Change |
| D3 | Custody timeline | Ordered history of who held the evidence, when, why, with transaction IDs. | GET /api/evidence/{id}/chain-of-custody | P0 | Exists |
| D4 | Mandatory reason and notes | Every transfer and status change needs a purpose and notes. | Bean Validation | P1 | Exists |
| D5 | Current custodian | The ledger record always shows who holds the item right now. | Chaincode field | P1 | New |

## E. Case management

| ID | Feature | What it does | Spring Boot / tech | Pri. | Prototype |
|---|---|---|---|---|---|
| E1 | Create and manage cases | Case number, title, description, lead officer, status (open/closed). | CaseController, Case entity | P0 | New |
| E2 | Assign officers to cases | Add or remove team members on a case, with a role on that case. | Case-membership table | P1 | New |
| E3 | Link evidence to cases | Evidence belongs to a real case entity (today caseId is only a string). Case summary shows all linked evidence. | JPA relation, ledger caseId | P0 | Change |
| E4 | Close and reopen cases | A closed case blocks new evidence and transfers until reopened by a Judge or Admin. | Case status guard | P2 | New |

## F. Storage and encryption

| ID | Feature | What it does | Spring Boot / tech | Pri. | Prototype |
|---|---|---|---|---|---|
| F1 | IPFS integration | Pin through a local node or Pinata, keep the CID, and handle fetch failures without losing ledger info. | IpfsClient (RestClient) | P0 | Exists |
| F2 | Envelope encryption | File encrypted with a per-file AES-256-GCM key. The key is wrapped for each authorised user, so a public CID alone reveals nothing. | JCA / Bouncy Castle | P1 | New |
| F3 | Key management | Store wrapped keys in the database. Re-wrap or revoke when access changes. | Key table, KeyService | P1 | New |
| F4 | Personal data off-chain | The ledger only holds IDs, hashes and CIDs. Personal data is kept off-chain because it cannot be erased once written. | Design rule + code review | P1 | New |
| F5 | Upload consistency and retry | Order the steps (hash, IPFS, ledger). If the ledger write fails, unpin the file. Retry on Fabric MVCC conflicts. | Compensation step, Spring Retry | P1 | New |

## G. Blockchain / ledger layer

| ID | Feature | What it does | Spring Boot / tech | Pri. | Prototype |
|---|---|---|---|---|---|
| G1 | LedgerService abstraction | One interface for all ledger calls. Fabric implementation now; a web3j implementation could be added later. | LedgerService, FabricLedgerService | P0 | New |
| G2 | Chaincode | CreateEvidence, UpdateEvidence, UpdateStatus, InitiateTransfer, AcceptTransfer, GetHistory, plus role checks and status validation. | Go / Java / JS chaincode | P0 | Change |
| G3 | Ledger event listener | Listen to chaincode or block events and sync them into Postgres. | Fabric Gateway event API | P1 | New |
| G4 | Health checks | Report Fabric, IPFS and database connectivity. | Spring Boot Actuator | P1 | New |

## H. Search, analytics and notifications (off-chain database)

| ID | Feature | What it does | Spring Boot / tech | Pri. | Prototype |
|---|---|---|---|---|---|
| H1 | Search and filter | caseId, status, officer, date range, full-text search, sorting and pagination, served from Postgres instead of the ledger. | Spring Data JPA, Specifications | P0 | Exists |
| H2 | Dashboard analytics | Counts by status, type, case and officer, and activity over time. | Aggregate queries | P1 | New |
| H3 | Activity feed | Recent actions across the cases a user can see. | Feed table fed by ledger events | P1 | New |
| H4 | Notifications | Pending transfers, status changes and tamper alerts (in-app, optional email). | Notification entity, JavaMailSender | P1 | New |

## I. Reporting and court readiness

| ID | Feature | What it does | Spring Boot / tech | Pri. | Prototype |
|---|---|---|---|---|---|
| I1 | Chain-of-custody PDF report | Evidence details, full timeline, hashes, transaction IDs and the verification result in one downloadable PDF. | OpenPDF / iText | P1 | New |
| I2 | QR code labels | QR code that encodes the evidence ID and verify link, for physical tags. | ZXing | P2 | New |
| I3 | Shareable verification link | A link a court or auditor can open to see integrity status only, without seeing the content. | Signed, expiring token | P2 | New |
| I4 | Electronic records certificate | Template certificate for digital evidence (Section 63 of the Bharatiya Sakshya Adhiniyam, earlier Section 65B). Confirm details with your guide. | PDF template | P2 | New |

## J. Dispute and review (stretch)

| ID | Feature | What it does | Spring Boot / tech | Pri. | Prototype |
|---|---|---|---|---|---|
| J1 | Flag evidence as disputed | An authorised user flags evidence with a reason. Status becomes DISPUTED and edits are frozen. | Status + chaincode rule | P2 | New |
| J2 | Reviewer panel voting | N reviewers vote on the dispute. Simplified version of the DAO Jury on Fabric (random selection done in the backend). | Dispute + Vote entities | P2 | New |
| J3 | Record outcome | Store the result on the ledger. Token staking and slashing (Legal Ticket) stay as future work. | Chaincode function | P2 | New |

## K. Platform and security

| ID | Feature | What it does | Spring Boot / tech | Pri. | Prototype |
|---|---|---|---|---|---|
| K1 | Validation and error format | Bean Validation on all requests and one global error format. | @Valid, @RestControllerAdvice | P0 | New |
| K2 | API documentation | OpenAPI/Swagger UI and an updated Postman collection with a baseUrl environment. | springdoc-openapi | P0 | Change |
| K3 | Configuration and secrets | Environment variables and profiles for ports, IPFS URL, channel, chaincode, identity. No secrets in code. | application.yml, profiles | P0 | Exists |
| K4 | Rate limiting and upload limits | Limit requests per user and cap file sizes. | Bucket4j, multipart limits | P1 | New |
| K5 | HTTPS, CORS, security headers | Restrict origins to the frontend and set standard headers. | Spring Security config | P1 | New |
| K6 | Structured logging | JSON logs with a correlation ID per request. | Logback, MDC | P2 | New |

## L. DevOps and testing

| ID | Feature | What it does | Spring Boot / tech | Pri. | Prototype |
|---|---|---|---|---|---|
| L1 | Unit tests | Services, state machine, RBAC rules and hashing. | JUnit 5, Mockito | P0 | New |
| L2 | Integration tests | Real Postgres and mocked IPFS/Fabric for the main flows. | Testcontainers, WireMock | P1 | New |
| L3 | Docker Compose | One command to start the backend, Postgres, IPFS and the Fabric test network for the demo. | docker-compose.yml, Dockerfile | P1 | New |
| L4 | CI pipeline | Build and test on every push. | GitHub Actions | P2 | New |


## Changes needed to the existing Node.js endpoints

| Endpoint | Change |
|---|---|
| POST /api/evidence | Take officer from the JWT, generate the evidence ID on the server, compute and store the file hash, accept a file upload. |
| POST /api/evidence/bulk | Keep it. Return a result per item so one failure does not hide the others. |
| GET /api/evidence | Serve from Postgres (synced from ledger events) so search, filters and pagination stay fast. |
| GET /api/evidence/:id | Also return verification status and the current custodian. Keep support for lookup by CID. |
| PUT /api/evidence/:id | Becomes a versioned update. A reason is required and older versions stay readable. |
| POST /api/evidence/:id/status | Enforce the status state machine in the service and in the chaincode. |
| GET / POST /api/evidence/:id/chain-of-custody | Keep the timeline. Transfers become two-step: initiate, then accept or reject. |
| DELETE /api/evidence/:id and DELETE /api/evidence/bulk | Remove. Replace with an archive/dispose request that needs a reason and approval. |
| (new) GET /api/evidence/:id/verify | Re-hash the stored file and compare with the ledger. |
| (new) GET /api/evidence/:id/history | Ledger version history with transaction IDs. |

## Implemented vs future work (for the report)

Examiners will compare the report with the demo. Keep an honest split so nothing is claimed that is not built.

| Category | Features |
|---|---|
| Implement now (Fabric + Spring Boot) | JWT login and per-user identities, RBAC, evidence registration with file upload, IPFS storage, SHA-256 verification, versioned updates, ledger history, status state machine, two-step custody transfer, cases, search and dashboard, PDF custody report, tests and Docker Compose. |
| Stretch (if time allows) | Envelope encryption with key wrapping, dispute flag with reviewer voting, QR labels, shareable verification link, electronic records certificate. |
| Future work (describe, do not claim) | Polygon L2 deployment, Chainlink VRF for random jury selection, token staking and automatic slashing (Legal Ticket), Web3Auth hidden wallets, gasless relayer, Stripe fiat subscriptions, deepfake detection for the oracle problem. |

## Suggested build order

| Phase | Work | Features |
|---|---|---|
| Phase 1 - Foundation | Project setup, error format and validation, users and JWT, roles, LedgerService with Fabric, IPFS client, Actuator health. | K1, K3, A1, A3, G1, F1, G4 |
| Phase 2 - Core evidence | Register with file upload, hash, retrieve, verify, versioned update, archive/dispose, chaincode changes. | B1-B5, C1-C3, G2 |
| Phase 3 - Custody and cases | Status state machine, two-step transfer, custody timeline, cases and assignments, per-user Fabric identity. | D1-D3, E1-E3, A2 |
| Phase 4 - Off-chain sync | Ledger event listener, Postgres search and filters, dashboard, activity feed, notifications, audit log. | G3, H1-H4, A6 |
| Phase 5 - Finish | PDF report, encryption, tests, Docker Compose, Swagger docs, updated Postman collection. Stretch items last. | I1, F2-F5, L1-L3, K2 |

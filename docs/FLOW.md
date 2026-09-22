# Flow

How execution travels through the code. Update whenever a call path is added or changed; mark the part
being modified with **[MODIFYING]**.

**Status 2026-09-22:** Phase 1 paths (sections 1-5) and Phase 2 paths (sections 7-11) are implemented and verified live,
the Phase 2 paths now against the **real Fabric ledger** (section 12) as well as the in-memory reference ledger. Nothing is
marked [MODIFYING]. Phase 3 has not been started.

Package prefix `com.blockevidence.backend` is omitted. Feature IDs refer to `docs/FEATURE_LIST.md`.

## 1. Every request: the filter chain (A1, A3, K1)

```
HTTP request
 └─ Spring Security filter chain                                        security/SecurityConfig
     ├─ JwtAuthenticationFilter.doFilterInternal
     │    ├─ header "Authorization: Bearer <jwt>" present?
     │    │    ├─ yes → JwtService.parse(token)  → AuthenticatedUser(userId, email, role)
     │    │    │        → SecurityContext = authenticated, authority ROLE_<role>
     │    │    └─ parse fails (bad signature / expired / wrong issuer / unsigned / bad claim)
     │    │             → context left EMPTY (no error here)
     │    └─ chain.doFilter
     ├─ authorizeHttpRequests (default-deny)
     │    ├─ permitAll: POST /api/auth/login, POST /api/auth/refresh, /actuator/health[/**]
     │    └─ everything else: must be authenticated
     │         └─ not authenticated → ApiAuthenticationEntryPoint → 401 ApiError (via ApiErrorWriter)
     └─ DispatcherServlet → Controller
          └─ @PreAuthorize (method security) fails → AccessDeniedException
                → GlobalExceptionHandler.handleAccessDenied → 403 ApiError
```
Filter-level 401/403 use ApiErrorWriter; in-MVC exceptions use GlobalExceptionHandler; both emit the
same ApiError body (K1).

## 2. Login (A1)

```
POST /api/auth/login
 └─ AuthController.login(@Valid LoginRequest)                           400 VALIDATION_FAILED if invalid
     └─ AuthService.login                                    @Transactional
         ├─ AuthenticationManager.authenticate                          (ProviderManager → DaoAuthenticationProvider)
         │    ├─ DatabaseUserDetailsService.loadUserByUsername → UserRepository.findByEmailIgnoreCase
         │    └─ BCrypt compare; disabled account rejected
         │    any AuthenticationException → AuthenticationFailedException("Invalid email or password") → 401
         │    (InternalAuthenticationServiceException, e.g. DB down, is rethrown → 500)
         ├─ UserRepository.findByEmailIgnoreCase
         └─ issueTokens(user)
              ├─ JwtService.issueAccessToken      (HS256, 15 min)
              └─ RefreshTokenRepository.save(SHA-256 hash of new random token, expires +7d)
     ◄── TokenResponse { accessToken, refreshToken, tokenType, expiresIn }
```

## 3. Refresh-token rotation (A1)

```
POST /api/auth/refresh
 └─ AuthController.refresh(@Valid RefreshTokenRequest)
     └─ AuthService.refresh        @Transactional(noRollbackFor = AuthenticationFailedException)
         ├─ RefreshTokenRepository.findByTokenHash(sha256(presented))     not found → 401
         ├─ already revoked?  → revokeAllForUser(user) → 401              (reuse = presumed theft)
         ├─ expired?          → 401
         ├─ RefreshTokenRepository.revokeIfActive(id)   atomic UPDATE ... WHERE revoked_at IS NULL
         │      returns 0 (lost a race) → revokeAllForUser(user) → 401
         ├─ UserRepository.findById; missing or disabled → revokeAllForUser(user) → 401
         └─ issueTokens(user)                                              (same as login)
```
`noRollbackFor` matters: the revocations above must commit even though an exception is thrown after them.

## 4. Logout and /me (A1, C-05)

```
POST /api/auth/logout            (needs access token)
 └─ AuthController.logout(@AuthenticationPrincipal AuthenticatedUser, RefreshTokenRequest)
     └─ AuthService.logout(user.userId(), token)   revokes only if the token belongs to that user; otherwise silent no-op → 204

GET /api/auth/me                 (needs access token)
 └─ AuthController.me(@AuthenticationPrincipal AuthenticatedUser)
     └─ AuthService.me(user.userId())  → UserRepository.findById → MeResponse (never the password hash)
```
The acting user's id comes from the verified token only (constraint C-05).

## 5. Health (G4)

```
GET /actuator/health
 └─ HealthEndpoint aggregates (StatusAggregator default order: DOWN > OUT_OF_SERVICE > UP > UNKNOWN)
     ├─ db      Spring DataSourceHealthIndicator
     ├─ ipfs    storage/IpfsHealthIndicator → IpfsClient.isReachable()
     │            → HttpIpfsClient: POST {ipfs.api-url}/api/v0/version, timeout 3s → true/false
     ├─ ledger  ledger/LedgerHealthIndicator → LedgerService.health()
     │            → FabricLedgerService (stub): UNKNOWN + detail
     └─ (Spring built-ins: diskSpace, ping, ssl, liveness/readiness)
 anonymous / non-ADMIN: overall status only.   ADMIN: components and details.
 any component DOWN → overall DOWN, HTTP 503.
```

## 6. Seams (G1, F1)

```
EvidenceService ──► LedgerService (interface) ◄── FabricLedgerService  DEFAULT: real Fabric (section 12)
                                              ◄── InMemoryLedgerService  only with profile memory-ledger (reference/dev)
EvidenceService ──► IpfsClient    (interface) ◄── HttpIpfsClient        real: pin / read / unpin / isReachable
```
Callers of the two interfaces: `EvidenceService`, `VerificationService`, and the health indicators.

## 7. Register evidence (B1, B2, C1)

```
POST /api/evidence   multipart: JSON part "metadata" + optional binary part "file"
 └─ Spring multipart limit (50 MB) ............................................. 413 if exceeded
 └─ JwtAuthenticationFilter -> AuthenticatedUser (the ONLY source of the registrant, C-05)
 └─ EvidenceController.register  @PreAuthorize(COLLECTOR|FORENSIC_ANALYST)      403 otherwise
     └─ @Valid RegisterEvidenceRequest (no collector field exists)              400 VALIDATION_FAILED
     └─ EvidenceService.register
         ├─ DIGITAL needs a file / PHYSICAL forbids one / content type on allow-list   400/400/415
         ├─ evidenceId = "EV-" + UUID (server-generated)
         ├─ storeFile:  HashingInputStream(file) ─► IpfsClient.pin ─► fileCid        C1: sha256 + byte count computed WHILE streaming
         ├─ storeMetadata: JSON bytes ─► sha256 ─► IpfsClient.pin ─► metadataCid
         ├─ LedgerService.createEvidence(LedgerNewEvidence, LedgerActor)   ledger written LAST
         │      on ANY failure: compensate(pins)  = unpin only CIDs the ledger does not reference
         └─ get(evidenceId, false)  ─► response (201)
```

## 8. Retrieve (B3), versions (B4), history (C3)

```
GET /api/evidence/{id}[?verify=true]      any authenticated role
 └─ EvidenceService.get ─► LedgerService.getEvidence ........ absent -> 404
     ├─ IpfsClient.read(metadataCid) ─► EvidenceMetadata     IPFS failure -> metadataAvailable=false (ledger data still returned, F1)
     └─ verify=true ? VerificationService.verify : NOT_CHECKED
GET /api/evidence/by-cid/{cid}   ─► LedgerService.findEvidenceIdsByCid ─► get(id) for each      404 if none
GET /api/evidence/{id}/versions/{n}  ─► LedgerService.getHistory ─► entry with version n ─► metadata of THAT version
GET /api/evidence/{id}/history       ─► LedgerService.getHistory ─► version, txId, ledger timestamp, action, actor, reason
```

## 9. Verify (C2)

```
GET /api/evidence/{id}/verify
 └─ EvidenceService.verify ─► LedgerService.getEvidence (404 if unknown)
     └─ VerificationService.verify(record)
         ├─ file (DIGITAL only):  IpfsClient.read(fileCid, Sha256::hex)  vs record.fileSha256
         ├─ metadata:             IpfsClient.read(metadataCid, Sha256::hex)  vs record.metadataSha256
         │      ContentNotFoundException -> component NOT_FOUND;  StorageUnavailableException -> 503 (NOT a verdict)
         └─ overall = TAMPERED > NOT_FOUND > VERIFIED
```

## 10. Versioned update (B4)

```
PUT /api/evidence/{id}   {expectedVersion, reason, description?, location?, notes?}     COLLECTOR|FORENSIC_ANALYST
 └─ EvidenceService.update
     ├─ getEvidence; version != expectedVersion -> 409 VERSION_CONFLICT (before touching IPFS)
     ├─ readVerifiedMetadata: read current document, sha256 must equal ledger hash else 409 INTEGRITY_CHECK_FAILED
     ├─ merge changed fields; metadataVersion+1; previousMetadataCid = current
     ├─ storeMetadata (NEW document; old one untouched) ─► LedgerService.updateEvidence(id, expectedVersion, newCid, newSha, reason, actor)
     └─ on failure: compensate(new pin)
```

## 11. Disposal instead of delete (B5)

```
POST /api/evidence/{id}/disposal          {expectedVersion, reason}   COLLECTOR|PROSECUTOR  ─► LedgerService.requestDisposal
POST /api/evidence/{id}/disposal/approve  {expectedVersion, note}     JUDGE ─► LedgerService.approveDisposal  ─► status DISPOSED, record frozen
POST /api/evidence/{id}/disposal/reject   {expectedVersion, note}     JUDGE ─► LedgerService.rejectDisposal
DELETE anywhere under /api/evidence  ─► 405 (no such mapping exists, C-02)
```
Nothing is unpinned from IPFS at any point.

## 12. The real ledger path (G1, G2): BUILT and verified 2026-09-22

```
LedgerService.<write>  (EvidenceService)
 └─ FabricLedgerService.submit(fn, args...)                     args: ids/hashes/CIDs + actorId + actorRole (from the JWT, C-05)
     └─ contract().submitTransaction  (Fabric Gateway, gRPC over TLS to peer0.org1 :7051, identity = User1@org1, one for all users)
         ├─ ENDORSE: the gateway picks peers of BOTH orgs; each runs chaincode `evidence` (Go) in its own container
         │     └─ EvidenceContract.<Fn> ─ authorise(): MSP in {Org1MSP, Org2MSP}?  actor well-formed?  role allowed for this fn?  (C-08)
         │            ─ loadEditable(): exists?  not DISPOSED?  expectedVersion == record version?
         │            ─ validate args ─ stamp() = tx timestamp + txId ─ PutState(EV~id) ─ PutState(CID~cid~id) ─ SetEvent
         │     endorsers disagree or a check fails -> error "CODE: message" -> FabricErrors.translate -> LedgerException(code)
         ├─ ORDER: orderer.example.com batches the transaction into a block (~2 s batch timeout)
         └─ COMMIT: each peer validates (endorsement policy, MVCC) and applies it; submitTransaction returns only now
              MVCC_READ_CONFLICT (two writers, same key, same block) -> CommitException -> VERSION_CONFLICT (409)
     ◄── TxResult{txId, timestamp, version} -> LedgerTxResult

LedgerService.<read> ─ FabricLedgerService.evaluate ─ contract().evaluateTransaction (ONE peer, nothing ordered)
   GetEvidence / GetHistory (peer history index, sorted by version) / FindByCid (composite-key range scan)
   EVIDENCE_NOT_FOUND -> Optional.empty() for getEvidence, 404 elsewhere
Health: evaluate GetEvidence(EV-0000...) ; EVIDENCE_NOT_FOUND = healthy (the chaincode answered)
Failure mapping: gRPC UNAVAILABLE/DEADLINE -> LEDGER_UNAVAILABLE (503); identity paths unset -> LEDGER_UNAVAILABLE / health UNKNOWN
```
The connection is created lazily on first use and survives peer restarts (verified: ~4 s recovery without restarting the app).

## 13. Change status (D1)
1. `POST /api/evidence/{id}/status` -> JwtAuthFilter (section 1) -> `@PreAuthorize` CHANGE_STATUS (COLLECTOR, FORENSIC_ANALYST, PROSECUTOR); body validated (`expectedVersion`, `status`, non-blank `reason`).
2. `StatusService`: target DISPOSED -> 400; `EvidenceStatus.canMoveTo(current, target)` false -> 409 `INVALID_STATE` (reads the record first, no transaction spent).
3. `LedgerService.updateStatus(id, expectedVersion, status, reason, LedgerActor(userId, role))`, the actor from the token (C-05).
4. Chaincode `UpdateStatus` re-checks role, version, transition and frozen DISPOSED, writes version+1 with `STATUS_CHANGED`, emits an event.
5. Response = the new record (with the ledger txId in `/history`).

## 14. Custody transfer (D2)
1. Initiate: `POST /api/evidence/{id}/transfers`. `CustodyService` loads the receiver from PostgreSQL (`RECEIVER_NOT_FOUND` 404 / `RECEIVER_CANNOT_HOLD_EVIDENCE` 400), then `LedgerService.initiateTransfer(id, v, toUserId, toRole, reason, notes, actor)`. Chaincode: actor must be the current custodian (and a custody-holding role), no pending transfer/disposal, not DISPOSED, receiver differs from sender; writes `PENDING` + index `TRF~receiver~id`.
2. The receiver sees it in `GET /api/transfers/pending` (`FindPendingTransfers(userId)` -> ids -> `getEvidence` each).
3. Accept: `POST .../transfers/accept` (only the named receiver, id and role must match) -> `currentCustodian` = receiver, state ACCEPTED. Reject (receiver) / cancel (sender) -> state closes, custody unchanged.
4. Every step is one transaction = one history entry (section 15).

## 15. Custody timeline (D3)
`GET /api/evidence/{id}/chain-of-custody` -> `LedgerService.getHistory(id)` (the peer's history index; 404 if unknown) -> `CustodyService` maps entries whose `lastAction` is CREATED or TRANSFER_* to events (`CUSTODY_STARTED`, `TRANSFER_INITIATED/ACCEPTED/REJECTED/CANCELLED`) with `custodianAfter`, ledger txId and timestamp. No database read for the events.

## 16. Cases (E1, E2)
`/api/cases...` -> JwtAuthFilter -> `@PreAuthorize` MANAGE_CASES for writes (ADMIN, PROSECUTOR), any authenticated user for reads -> `CaseService` (`@Transactional`) -> `CaseFileRepository` / `CaseMemberRepository` / `UserRepository` (PostgreSQL only; the ledger is never called). Lead officer is validated (exists, enabled, a working role) and always a member with role LEAD_OFFICER; changing the lead flips roles in place. Evidence and cases are not linked yet (E3, waiting for the owner).

## 17. Register evidence, updated for E3
Step 1 of section 7 (register) now starts with `EvidenceService.register` resolving `request.caseId()` against
`CaseFileRepository.findByCaseNumberIgnoreCase` BEFORE any IPFS work; an unknown case number is `404 CASE_NOT_FOUND` and
nothing is pinned. After `ledger.createEvidence` succeeds (unchanged from section 7), `CaseEvidenceLinkRepository.save`
writes the off-chain link row. `GET /api/cases/{id}/evidence` -> `CaseService.evidence` -> `CaseEvidenceLinkRepository` for
the linked ids -> `EvidenceService.get` for each (the same read path as `findByCid`, one ledger call per item).

## 18. Every write, updated for A2

Every write path in sections 7, 10, 11, 13, 14 (register, update, status, disposal, custody transfer) is unchanged
UP TO the point where it calls `LedgerService`. From there: `FabricLedgerService.submit(actor, function, args...)` ->
`contractFor(actor)` -> `IdentityStore.find(actor.userId())`. No wallet entry -> `403 LEDGER_IDENTITY_MISSING`,
nothing sent to the chaincode. A wallet entry found -> a per-user `Gateway` (cached) signs and submits the
transaction -> the chaincode's `authorise()` reads the certificate's `role`/`hf.EnrollmentID`, checks it equals the
`actorId`/`actorRole` arguments Spring still sends (unchanged), and only then checks the role-permission table
(unchanged). Every READ (`GetEvidence`, `GetHistory`, `FindByCid`, `FindPendingTransfers`, the health probe) is
completely unaffected: they still use the single SERVICE identity, as before A2.

## 19. Ledger event sync (G3)

```
EventSyncListener.run() (ApplicationRunner, on its own single-thread executor)
 └─ loop: runOnce()
     ├─ CheckpointStore read (ledger_sync_checkpoint)                     empty -> Checkpoint.NONE
     ├─ LedgerEventSource.openEventStream(checkpoint)                     FabricLedgerService: startBlock(0) or .checkpoint(...)
     │     (only ledger/ talks to Fabric here too, C-01)
     ├─ for each LedgerEvidenceEvent (blocking):
     │     ├─ LedgerService.getHistory(evidenceId) -> find entry by txId   (enrichment; OUTSIDE any DB transaction)
     │     ├─ applyWithLocalRetry: up to 3x, 200ms fixed pause             (tier 1, design section 9)
     │     │     └─ EventProcessor.apply(event, record)  @Transactional
     │     │           ├─ EvidenceActivityRepository.insertIfAbsent (ON CONFLICT tx_id DO NOTHING)   0 rows -> stop here
     │     │           ├─ EvidenceProjectionRepository.upsert (compare-and-set on version)
     │     │           └─ LedgerSyncCheckpointRepository.advance
     │     └─ tier 1 exhausted -> escalate (caught by runOnce's catch)
     └─ any exception reaching runOnce's catch: close the stream, exponential backoff (tier 2, design section 9),
        loop again from a freshly re-read checkpoint
```
`EventSyncHealthIndicator` reads `EventSyncListener`'s in-memory failure counter for `/actuator/health` component
`eventSync`. Nothing else in the request-handling flow (sections 1-18) changes: G3 only reads the ledger.

## 20. H1-H4 and A6

```
GET /api/evidence/search  -> EvidenceController.search -> SearchService -> EvidenceProjectionRepository (Specification)
GET /api/dashboard        -> DashboardController -> DashboardService -> EvidenceProjectionRepository (aggregate)/
                                                                          EvidenceActivityRepository (by-day aggregate)
GET /api/activity         -> ActivityController -> ActivityService -> EvidenceActivityRepository (paged, ledger_at DESC)
GET/POST /api/notifications -> NotificationController -> NotificationService -> NotificationRepository (own rows only)

evidence_activity/projection writes (G3, section 6) -> EventProcessor.apply, after the checkpoint advance:
    NotificationService.notifyForEvent(event, record)
        TRANSFER_INITIATED   -> one notification, to record.transfer().to()
        STATUS_CHANGED/DISPOSAL_* -> CaseFileRepository.findByCaseNumberIgnoreCase(record.caseId())
                                     -> CaseMemberRepository -> one notification per member
        anything else -> no notification

EvidenceService.get/verify -> verification.verify(record) -> result.status()==TAMPERED?
    -> NotificationService.notifyTamperAlert(evidenceId, caseId, currentCustodian)   (NOT a ledger event)

Every authenticated request that reaches EvidenceController.get/verify:
    -> AuditService.recordAccess(id, download?) -> UserRepository.findById(actor).enabled?
        true  -> AuditAction.VIEW or DOWNLOAD
        false -> AuditAction.TOKEN_USED_AFTER_DEACTIVATION   (Phase 1 access-token-lag, visibility only)

A request refused before/inside MVC:
    ApiAuthenticationEntryPoint (401, pre-DispatcherServlet) -> AuditService.recordDenied(AUTH_FAILED, ...)
    ApiAccessDeniedHandler (403, pre-DispatcherServlet)      -> AuditService.recordDenied(ACCESS_DENIED, ...)
    GlobalExceptionHandler.handleAuthenticationFailed (401, bad login) -> AuditService.recordDenied(AUTH_FAILED, ...)
    GlobalExceptionHandler.handleAccessDenied (403, @PreAuthorize)     -> AuditService.recordDenied(ACCESS_DENIED, ...)

GET /api/audit -> AuditController -> AuditLogRepository (Specification: userId/action/from/to), ADMIN/AUDITOR only
```

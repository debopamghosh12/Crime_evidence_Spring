# Flow

How execution travels through the code. Update whenever a call path is added or changed; mark the part
being modified with **[MODIFYING]**.

**Status 2026-09-23:** Phase 1 paths (sections 1-5) are implemented and verified live. Phase 2 Spring-side
paths (sections 7-11) are implemented and verified live **against the in-memory reference ledger**.
**[NOT BUILT, AWAITING APPROVAL]:** the real Fabric path in section 12 (`FabricLedgerService` and the
chaincode); until it exists, the default profile answers evidence calls with 501 NOT_IMPLEMENTED.

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
EvidenceService ──► LedgerService (interface) ◄── FabricLedgerService  every operation throws
                                                   LedgerNotImplementedException -> 501   [AWAITING G2 APPROVAL]
                                              ◄── InMemoryLedgerService  only with profile memory-ledger
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

## 12. [NOT BUILT, AWAITING G2 APPROVAL] Real ledger path
`LedgerService` (interface) -> `FabricLedgerService` -> fabric-gateway (gRPC, Org1 peer) -> chaincode `evidence`
(Go). Designed in `docs/CHAINCODE_DESIGN.md`; not implemented. Do not treat as current behaviour.

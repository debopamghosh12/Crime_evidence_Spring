# Flow

How execution travels through the code. Update whenever a call path is added or changed; mark the part
being modified with **[MODIFYING]**.

**Status 2026-09-22:** all Phase 1 paths below are implemented and verified live. Nothing is marked
[MODIFYING]. Phase 2 (evidence, files, ledger) has not been started.

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

## 6. Stubs (G1, F1): the seams later phases fill in

```
(future) Service ──► LedgerService (interface)  ◄── FabricLedgerService   every operation throws
                                                     LedgerNotImplementedException → 501 NOT_IMPLEMENTED
(future) Service ──► IpfsClient    (interface)  ◄── HttpIpfsClient        pin/fetch/unpin throw
                                                     StorageNotImplementedException → 501; isReachable is real
```
No controller or service calls these yet, so the only Phase 1 caller is the health path in section 5.

## 7. Target flows (NOT BUILT, shown for orientation; see ARCHITECTURE.md 5.4)
Register evidence, verify, custody transfer and the rest arrive in Phase 2 and later. Do not treat them as
current behaviour.

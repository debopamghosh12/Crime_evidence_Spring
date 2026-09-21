# A3: Roles and RBAC scaffolding

**FEATURE_LIST:** A3 (P0, new). **Phase:** 1, scaffolding only. **Status:** done, verified 2026-09-22.

## Scoping
A3 says roles are "checked in Spring and checked again inside the chaincode". Phase 1 builds the Spring
half of the *mechanism* only. It deliberately does **not** define who may do what to which evidence:
those rules belong to the features that need them (D1 status changes, B5 disposal, ...) and must be
mirrored in the chaincode ACL in Phase 2, so they are written next to those features.

## What was built
- `security/Role`: `COLLECTOR, FORENSIC_ANALYST, PROSECUTOR, JUDGE, AUDITOR, ADMIN`; `authority()` returns
  `ROLE_<NAME>`. Stored as a string in `users.role` with a DB CHECK constraint.
- `@EnableMethodSecurity` on `SecurityConfig`, so later controllers/services use
  `@PreAuthorize("hasRole('JUDGE')")` etc.
- `security/AuthenticatedUser(userId, email, role)`: the only source of "who is acting" (constraint C-05).
- Default-deny URL rules: everything needs authentication except login, refresh and `/actuator/health`.

## Decisions and traps
- One role per user (single enum column), not many-to-many: simplest thing that satisfies A3/A4.
  Revisit only if a real user needs two roles.
- The role is read from the verified JWT, so a role change reaches an existing access token only after
  it expires (max 15 min). Recorded as a known limitation.
- `AccessDeniedException` from `@PreAuthorize` occurs inside MVC, so it needed an explicit handler
  (see K1) or it would surface as 500.
- No throwaway endpoint ships to demonstrate RBAC: a **test-only** controller
  (`src/test/.../RbacProbeController`) exercises it.
- Slice-test trap: `@WebMvcTest` does not process `@ConfigurationPropertiesScan`, so `JwtProperties` was
  missing and 17 tests failed at context startup. Fixed by `@EnableConfigurationProperties` on the test.
  The full application registers it normally (confirmed by the live run).

## Verification
`SecurityAndErrorFormatTest` (real security filter chain via MockMvc, real `JwtService`): admin-only route:
COLLECTOR -> 403 `ACCESS_DENIED`, ADMIN -> 200. `hasAnyRole('JUDGE','AUDITOR')`: JUDGE and AUDITOR 200;
COLLECTOR, FORENSIC_ANALYST, PROSECUTOR, ADMIN all 403 (note: ADMIN is not implicitly all-powerful; A4
says an Admin cannot edit evidence). Expired, wrong-secret and garbage tokens all 401. Result:
`Tests run: 17, Failures: 0, Errors: 0`.

## Known limits
No permission matrix, no case-level access (A5), no chaincode-side check (Phase 2).

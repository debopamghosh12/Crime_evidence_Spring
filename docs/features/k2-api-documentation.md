# K2: API documentation (springdoc-openapi/Swagger + Postman)

**FEATURE_LIST:** K2 (P0, "springdoc-openapi"). **Phase:** 5 (last item). **Status:** done, verified live -
Swagger UI in a real browser, the full Postman collection run three times via Newman against the real stack.

## What was built
- **springdoc-openapi-starter-webmvc-ui 2.8.6** (owner-approved new dependency). `config/OpenApiConfig` supplies
  the top-level info and the one `bearerAuth` security scheme every protected endpoint shares; every controller
  gained `@Tag` (class level) and `@Operation`/`@ApiResponse` (method level) with REAL, specific text - not
  auto-generated stubs. `SecurityConfig` permits `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs*` without a
  token (the docs are browsable; using "Try it out" still needs a real one).
- **`postman/BlockEvidence.postman_collection.json`** rebuilt from scratch to match every endpoint actually live
  in the controllers today (Phase 1-5, not a stale Phase 1-2 snapshot) - 42 requests across 5 folders (Auth,
  Cases, Evidence, Custody & Status, Activity/Dashboard/Notifications/Audit), matching the same tag grouping as
  Swagger. `postman/BlockEvidence.postman_environment.json` provides the `baseUrl` variable the owner asked for,
  plus per-role token variables chained automatically by each request's own test script.
- **`postman/sample-evidence.txt`**: a small file the DIGITAL-registration request attaches automatically, so
  the collection runs unattended (Newman/CI) without a file needing to be picked manually in the Postman UI.

## Reflecting reality, not aspiration (the owner's explicit ask)
Every `@Operation` description states the ACTUAL behaviour and the ACTUAL `@PreAuthorize` role requirement in
plain language (springdoc does not translate Spring Security SpEL automatically). The four flows named
specifically:
- **Register (F2/F3):** documents that encryption is automatic - a fresh content key, both artifacts encrypted
  before pinning, wrapped for the registrant (mandatory) and current case members (best-effort) - and that the
  ledger's hash covers the ciphertext.
- **Two-step custody transfer:** initiate/accept/reject/cancel each state exactly who may call them (current
  custodian vs. named receiver vs. original sender) and that custody does not move until accept.
- **Disposal approval:** request (COLLECTOR/PROSECUTOR) vs. decide (JUDGE only), and that DISPOSED is reachable
  no other way.
- **`/verify` and `/report`:** both explicitly documented as needing NO content key - the one property that most
  differs from every other read, and the reason a Judge/Auditor can use them regardless of F2/F3 access.

## Found live, fixed before this was "done"
- The Postman collection's own `expectedVersion` sequence collided across folders on a full run: the Evidence
  folder's disposal flow left the shared evidence item at version 4 (DISPOSED) before Custody & Status's
  `expectedVersion: 1` request ran against the SAME item. Fixed by having that folder register its own
  independent item.
- The DIGITAL-registration request had no file actually attached (empty `src`) - would silently fail
  `FILE_REQUIRED` under automated (Newman) execution. Fixed with the checked-in sample file.
- "Find by CID" depended on a `{{fileCid}}` variable nothing ever set. Fixed by having Register's own test
  script capture it from the response.

## Verification
1. **Swagger UI**, loaded in a real browser: correct title/description, all 8 tags, 33 operations with real
   summaries, zero console errors; expanded "Verify integrity" and confirmed its full rich description renders
   exactly as written.
2. **`/v3/api-docs`**: valid OpenAPI 3.1 JSON, 33 paths, `bearerAuth` scheme present, zero operations missing a
   summary (checked programmatically, not by inspection alone).
3. **The full Postman collection, run three times via Newman** (`npx newman run`, a one-off CLI tool for
   verification, not a new project dependency) against the real docker-compose stack (real Postgres, real IPFS,
   `memory-ledger`): **42/42 requests, 0 failed, 20/20 assertions**, consistently across all three runs -
   including login, register-with-encryption and generate-a-report, the three the owner asked to confirm
   specifically, plus every other endpoint chained together with real data end to end.

## Known limits
- The collection assumes a single, coherent run per environment reset - re-running WITHOUT restarting the
  backend (`memory-ledger` has no persistence, ARCHITECTURE section 19) works fine (each run creates its own
  fresh case/evidence via `{{$timestamp}}`), but running it against a real Fabric network repeatedly will
  accumulate real, permanent ledger test data (same class of note as every other live-verification script in
  this project).
- No automated CI execution of the Postman collection (L4, CI pipeline, is not built).

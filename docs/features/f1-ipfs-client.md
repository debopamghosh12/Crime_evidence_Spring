# F1: IPFS client (stub) and its reachability probe

**FEATURE_LIST:** F1 (P0, exists in prototype). **Phase:** 1, stub. **Status:** interface + probe done,
verified 2026-09-22. Pin/fetch/unpin arrive with file upload (B2, Phase 2).

## Scoping
"Pin through a local node or Pinata, keep the CID, and handle fetch failures without losing ledger info."
Phase 1 asked only for a stub, but G4 needs a real reachability check against IPFS, so `isReachable()` is
real and the rest throws.

## What was built
- `storage/IpfsClient` (interface): `pin(InputStream, fileName)`, `fetch(cid)`, `unpin(cid)`,
  `isReachable()`. Provisional signatures; `unpin` exists now because F5's compensation step needs it.
- `storage/HttpIpfsClient`: Spring `RestClient` against a Kubo RPC API. `pin/fetch/unpin` throw
  `StorageNotImplementedException` (501). `isReachable()` sends `POST {api-url}/api/v0/version`.
- `config/IpfsProperties`: `api-url` (default `http://localhost:5001`), `timeout` (default 3 s).
- Local node only; Pinata is a later, config-driven addition.

## Decisions and findings
- **Kubo's RPC API is POST-only** (a GET returns 405), so the probe is a POST. Verified against a real
  `ipfs/kubo:latest` container, and a fake node in the unit test answers GET with 405 so a regression to
  GET fails the test.
- **Explicit connect and read timeouts** via `SimpleClientHttpRequestFactory`: without them a hung node
  hangs the health probe. Tested: a node that takes 3 s is reported unreachable in < 2 s with a 300 ms
  timeout.
- `RestClient` from spring-web, so no new dependency (D-013).
- The Kubo API port is bound to `127.0.0.1` in the dev container: that RPC API has no authentication and
  must not be exposed to a network.

## Verification
`HttpIpfsClientTest` (5): reachable on POST 200; not reachable on 500; not reachable when nothing listens;
timeout honoured; stubs throw `StorageNotImplementedException`. Live against real Kubo:
```
GET /actuator/health (ADMIN, node up)      -> "ipfs":{"status":"UP"}
docker stop be-ipfs
GET /actuator/health (ADMIN, node stopped) -> HTTP 503  "ipfs":{"details":{"detail":"IPFS node not reachable"},"status":"DOWN"}
docker start be-ipfs
GET /actuator/health (ADMIN, node back)    -> HTTP 200  "ipfs":{"status":"UP"}
```

## Known limits
No authentication to the IPFS node (not needed locally; Pinata needs a token, Phase 2+). `isReachable()`
logs a WARN per failed probe, which can be noisy if a load balancer polls a dead node frequently.

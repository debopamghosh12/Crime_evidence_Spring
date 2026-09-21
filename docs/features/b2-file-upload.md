# B2: File upload (multipart, size and type limits, pinned to IPFS)

**FEATURE_LIST:** B2 (P0, new). **Phase:** 2. **Status:** done, verified live against real Kubo.

## Scoping
"Upload the digital evidence file (multipart) with size and type limits, then pin it to IPFS." It shares the
endpoint with B1: the file is the optional second part of `POST /api/evidence`, required for DIGITAL and
forbidden for PHYSICAL.

## What was built
- **Size limit:** `spring.servlet.multipart.max-file-size` (default 50 MB, env `UPLOAD_MAX_FILE_SIZE`) and
  `max-request-size` (52 MB). Enforced by the servlet container before any controller code runs.
- **Type limit:** `UploadProperties.allowedContentTypes` (images, PDF, text, audio, video, zip), checked on the
  client-declared content type with parameters stripped (`text/plain; charset=utf-8` matches `text/plain`).
- **Pinning:** `HttpIpfsClient.pin` streams the file to Kubo `POST /api/v0/add?cid-version=1&raw-leaves=true`
  and returns the CIDv1. Real `IpfsClient` (final signatures, D-016); `read`, `unpin`, `isReachable` beside it.
- Hostile filenames are reduced to a bare name (no directory part, no control characters, 200 chars) before
  they reach IPFS or the metadata document.

## Findings and decisions
- **Kubo behaviours measured, not assumed (Kubo 0.43):** API is POST-only; `add` answers one JSON line;
  `cat` of missing content is HTTP 500 "block was not found locally" (no 404); `pin/rm` of an unpinned CID is
  HTTP 500 "not pinned"; a default node joined the public network (D-020).
- **Type check is only as good as the client's claim** (D-026). `application/octet-stream` is excluded by
  default so the list means something; forensic disk images need it added to configuration.
- **`InputStreamResource` must not be subclassed**, or Spring reads the entire stream to learn its length
  before the upload starts (documented in the code and D-025).
- Empty files are rejected as "file required"; a 60 MB upload is rejected by the container as 413.

## Verification (TEST_CHECKLIST.md P2.2)
```
disallowed type (application/x-msdownload) -> 415  UNSUPPORTED_FILE_TYPE | File type 'application/x-msdownload' is not allowed
empty file for DIGITAL                     -> 400  FILE_REQUIRED
file on PHYSICAL evidence                  -> 400  FILE_NOT_ALLOWED
60 MB file (limit 50 MB)                   -> 413  CONTENT_TOO_LARGE
20 MB file -> HTTP 201 in 987 ms;  local sha256 == ledger sha256;  size=20000000;  verify -> VERIFIED
```
Automated: `HttpIpfsClientTest` (12: multipart body with filename, CID parsing, not-found vs unavailable,
idempotent unpin, malformed CID never sent, probe timeouts), `EvidenceServiceTest`.

## Known limits
- Not measured: heap use for a 50 MB upload under concurrent load (only a single 20 MB upload was timed).
- No virus/content scanning or magic-byte sniffing.
- The stored file is unencrypted and, on a networked node, publicly retrievable (F2 / D-020).

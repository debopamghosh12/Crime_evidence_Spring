# F2/F3 design: envelope encryption + key management

**Status:** proposed, awaiting owner approval (checkpoint, same as A2/G3 before it). No code written yet.
**Reads grounding this design:** `EvidenceService` (register/update/get/verify), `VerificationService`,
`IpfsClient`, `FileWalletIdentityStore` (A2's precedent for backend-custodied key material), `CaseService`
(addMember/removeMember), `CaseMember`, `User`, `DevUserSeeder`, `docs/features/f4-personal-data-off-chain-audit.md`.

## 1. What F2 actually protects, and what it does not

FEATURE_LIST's own words: "so the public CID alone reveals nothing." That is the threat model: someone who
obtains a CID *without going through this backend* (an IPFS crawler, a stranger on the swarm, anyone who scrapes
a CID off the API or the ledger) must not be able to read the file or the metadata document. This is exactly
C-09's risk (a default Kubo node serves pinned content to anyone who learns the CID) and directly follows from
the F4 audit's conclusion: the ledger itself is already clean (F4), the exposure is IPFS *content*.

F2/F3 do **not** protect against a compromised backend host, and do not add a new access-control layer beyond
what already exists (A5, case-level read restriction, is still not built - this design does not build it either,
though one side effect below narrows it slightly for `update`). This is the same trust boundary the project
already accepted for A2: `FileWalletIdentityStore` custodies every enrolled user's Fabric private key server-side
(CONSTRAINTS C-08, revised: "a compromised backend host can sign as any enrolled user"). This design custodies a
second kind of per-user private key the same way, for the same accepted reason: nothing about this project's
threat model changes by adding one more kind of key the backend holds.

## 2. New dependency check

**None needed.** Everything below is AES-256-GCM, RSA-OAEP, `KeyPairGenerator`, `Cipher`, `CipherInputStream`,
`SecureRandom` - stock JCA/JCE, already used by `Sha256`/`HashingInputStream`. BouncyCastle
(`bcpkix-jdk18on`, added for A2) is on the classpath already but is not even required for this feature; nothing
here needs PEM parsing or a non-default provider. FEATURE_LIST's own "JCA / Bouncy Castle" tech note is satisfied
by plain JCA.

## 3. Key hierarchy

Three tiers, cleanest-to-least-trusted:

1. **Evidence content key (ECK)** - one randomly generated AES-256 key per evidence item, created once at
   `register()`, never changes. Encrypts the file (if any) and *every* version of the metadata document for that
   evidence item (one DEK reused across versions, each encryption using a fresh random 96-bit GCM nonce - safe:
   a 256-bit key with random nonces has no meaningful collision risk at the handful of encryptions one evidence
   item will ever have). The raw ECK is **never persisted anywhere**; it exists in memory only for the duration
   of an encrypt or decrypt operation.
2. **Per-user keypair** - RSA-2048, one per user, generated once (at user creation - today that means
   `DevUserSeeder`; A4 would call the same provisioning method when it exists). The public key wraps an ECK for
   that user (`RSA/ECB/OAEPWithSHA-256AndMGF1Padding`); the private key unwraps it. Stored in a new `user_keys`
   table: public key in the clear (not sensitive), private key as PKCS8 DER encrypted with the master key (below).
3. **Master key** - one AES-256 key from configuration (`BLOCKEVIDENCE_ENCRYPTION_MASTER_KEY`, base64, new env
   var, analogous to `FabricProperties.walletPassphrase()`), held only in memory, protects every user's stored
   private key at rest. Same trust tier as the Fabric wallet passphrase already accepted (section 1).

Each evidence item's ECK is wrapped once per **authorised user** (section 5) as a `evidence_content_keys` row:
`RSA-OAEP(that user's public key, raw ECK bytes)`. "Wrapped per authorised user" (F2's own wording) means exactly
this: distinct RSA ciphertext per user, not one shared secret gated by an application check.

## 4. Storage layout (new Flyway migration, V6)

```sql
CREATE TABLE user_keys (
    user_id                 UUID PRIMARY KEY REFERENCES users(id),
    public_key               BYTEA NOT NULL,   -- X.509 SubjectPublicKeyInfo DER
    encrypted_private_key    BYTEA NOT NULL,   -- 12-byte IV || AES-256-GCM(master key, PKCS8 DER)
    created_at                TIMESTAMPTZ NOT NULL
);

CREATE TABLE evidence_content_keys (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evidence_id   VARCHAR(64) NOT NULL,        -- ledger evidenceId, e.g. EV-...
    user_id       UUID NOT NULL REFERENCES users(id),
    wrapped_key   BYTEA NOT NULL,              -- RSA-OAEP-SHA256(user's public key, raw 32-byte ECK)
    wrapped_at    TIMESTAMPTZ NOT NULL,
    UNIQUE (evidence_id, user_id)
);
CREATE INDEX ix_evidence_content_keys_evidence ON evidence_content_keys (evidence_id);
```

Both are ordinary PostgreSQL tables (F3's own wording: "store wrapped keys in the database"), not the ledger -
consistent with F4.

## 5. Where "authorised user" comes from, and the wrap/re-wrap/revoke triggers

FEATURE_LIST does not define "authorised"; the closest existing authorisation boundary in the codebase is case
membership (`CaseMember`/`CaseMemberRepository`, E2) - it is already what H4 uses to decide who gets a
notification for an evidence event on a case. This design proposes: **authorised = a member of the case the
evidence belongs to, plus the user who registered it** (who may not yet be a formal case member - `register()`
does not require that today, A5 is not built).

- **At `register()`:** wrap the new ECK for the registering user and for every current member of the case. Done
  in the SAME try/compensate block as `storeFile`/`storeMetadata`, BEFORE the ledger write - a wrap failure for
  the *registering user specifically* aborts registration and unpins, exactly like a storage failure today (F5's
  "order the steps" principle: nothing should exist on the ledger pointing at content nobody can ever read). A
  wrap failure for another *existing* case member is logged and skipped, not fatal to registration - the item is
  still readable by its registrar, matching this project's established compensation-not-blocking pattern
  (D-024/D-037, and E3's own off-chain-link residual).
- **`CaseService.addMember` (access GRANTED):** for every evidence item already linked to that case
  (`CaseEvidenceLinkRepository`, E3), if the new member has no wrapped-key row yet: take any ONE existing
  wrapped-key row for that item, decrypt that holder's private key with the master key, RSA-unwrap to recover the
  raw ECK momentarily, RSA-wrap it fresh under the new member's public key, store the new row, discard the raw
  ECK. This is the "re-wrap" F3 names, and it only works because the backend already custodies every private key
  (section 1) - no interactive step, no holder's cooperation needed. Best-effort per evidence item: `addMember`
  itself must not fail because key backfill had a hiccup on one item.
- **`CaseService.removeMember` (access REVOKED):** delete that user's `evidence_content_keys` row for every
  evidence item linked to the case. **The ECK itself is not rotated and content is not re-encrypted** - a
  deliberate, stated limit (section 8), the same shape of residual already accepted for A2 (CONSTRAINTS.md
  section B: "no peer-side certificate revocation... works until expiry").

**Open question for the owner:** should a custody **transfer** (`Transfer.to`, independent of case membership)
also wrap the ECK for the receiving user, the same way `addMember` does? Proposed default: yes, hooked into the
same place `EventProcessor` already resolves the receiver for H4's `TRANSFER_PENDING` notification - flag if
that default is wrong.

## 6. Flow changes

- **`register()`:** generate the ECK; `storeFile` wraps the file's `InputStream` in a `CipherInputStream`
  (AES/GCM/NoPadding, encrypt) ahead of the existing `HashingInputStream`, with a fresh random 12-byte IV
  prepended to what is actually pinned (`IV || ciphertext+tag`); `storeMetadata` does the same for the JSON bytes.
  **The ledger's `fileSha256`/`metadataSha256` becomes the hash of that IV||ciphertext blob, not the plaintext** -
  this is the key move that keeps `VerificationService` completely unchanged (section 7).
- **`update()` (new metadata version):** needs the ECK to encrypt the new version, so it must unwrap it first -
  using the ACTING user's own `evidence_content_keys` row and their master-key-decrypted private key. A user with
  no wrapped-key row for this evidence item cannot update it: **new 403 `KEY_NOT_AUTHORISED`.** This is a real
  behaviour change worth flagging explicitly (CLAUDE.md: flag anything touching established behaviour): today
  ANY authenticated role can update ANY evidence item (A5 gap, KNOWN_GAPS section D); this narrows *update*
  specifically for encrypted evidence to authorised users, as a side effect of needing the key, not a deliberate
  new authorisation feature. `readVerifiedMetadata`'s existing hash-check is unchanged (still checks the
  ciphertext against the ledger first); decryption is a new step inserted only after that check passes.
- **`verify()` / `get(..., verify=true)`:** **zero changes.** It already just re-fetches CID bytes and hashes
  them (`VerificationService.check`); since the ledger hash is now a ciphertext hash, this keeps proving tamper
  evidence for ANY caller, authorised or not - arguably a stronger property than today, since a non-authorised
  verifier can prove integrity without ever being able to read the plaintext.
- **New: `GET /api/evidence/{id}/file`** (not in FEATURE_LIST's own wording, but necessary - see below): streams
  the decrypted file to an authorised caller (their own wrapped-key row unwraps the ECK, `CipherInputStream` in
  decrypt mode reads `IV || ciphertext` from IPFS and streams plaintext out); 403 if the caller has no
  wrapped-key row for this item. Without this, F2 would encrypt files into content nobody can ever legitimately
  retrieve again, which cannot be the intent of an evidence system. Flagging this as new scope for approval,
  since strictly nothing in F2's FEATURE_LIST line asks for a download endpoint.

## 7. Why the ledger hash is over ciphertext, not plaintext

The alternative (hash the plaintext, as today) would force every `verify()` call to unwrap a key and decrypt
first, meaning: (a) `VerificationService` would need per-caller authorisation and key material, breaking its
current "works for anyone, no side effects" shape; (b) a non-authorised caller could no longer verify integrity
at all, which is a real capability loss (C2 currently makes no such distinction). Hashing the ciphertext keeps
C2's existing guarantee ("stored content matches what the ledger recorded") intact and adds a second, independent
integrity layer on top for authorised readers: AES-GCM's own authentication tag, checked automatically by
`CipherInputStream`/`Cipher.doFinal` the moment someone actually decrypts, which fails loudly
(`AEADBadTagException`) on any ciphertext corruption even in the (astronomically unlikely) event of a SHA-256
collision.

## 8. Known limits (to go in KNOWN_GAPS once built, matching the project's honesty pattern)

- **Revocation is not retroactive.** Removing a case member deletes their wrapped-key row; it does not rotate the
  ECK or re-encrypt content already on IPFS. Anyone who fetched and decrypted the file *before* revocation keeps
  what they already have - the same limitation as A2's certificate revocation gap (no CRL). True forward secrecy
  would mean re-encrypting under a new ECK and re-pinning on every membership change, which conflicts with this
  project's evidence-immutability stance (the ledger's `fileCid`/`fileSha256` would need to change) and is out of
  scope.
- **The backend can decrypt everything.** By design (section 1) - not a new residual, the same one C-08 already
  names for Fabric identities, now extended to content keys.
- **A wrap failure for a non-registering case member at register time is best-effort** (logged, not fatal) - a
  member who missed the initial wrap has no key until the next `addMember`-triggered backfill touches that item,
  or a future reconciliation sweep (none exists, same class of gap as D-024/D-037/E3).
- **No key rotation for the master key or for a compromised user keypair** - operational, not automated, same
  category as A2's certificate renewal.
- **PHYSICAL evidence has no file to encrypt** (unchanged); its metadata document still gets the same
  envelope-encryption treatment as DIGITAL evidence's metadata, since that is where description/location/notes
  live regardless of type.

## 9. Open questions for the owner (before implementation)

1. Confirm "authorised = case members + registrar" (section 5) is the right default, or name a different
   boundary.
2. Confirm the custody-transfer-receiver auto-wrap default (section 5).
3. Confirm adding `GET /api/evidence/{id}/file` (section 6) is in scope, or say if decrypted retrieval should
   wait for a later phase.
4. Confirm RSA-2048-OAEP-SHA256 for wrapping is an acceptable choice (vs. a larger RSA modulus or an EC-based
   scheme) - RSA-2048 was chosen for JCA-standard simplicity and because it needs no additional library.

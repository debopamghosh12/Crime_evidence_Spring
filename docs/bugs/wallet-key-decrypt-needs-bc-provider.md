# Bug: an encrypted wallet key fails to decrypt even though BouncyCastle is on the classpath

**Found:** 2026-09-22, first live A2 check (registering evidence signed with a real per-user wallet identity
against real Fabric, wallet keys written PKCS#8-encrypted by `enroll_users.sh`). **Fixed:** same day.
**Feature affected:** A2 (`FileWalletIdentityStore`, encrypted-key path only; unencrypted wallet keys were unaffected).

## Symptom

`POST /api/evidence` as the collector answered `403 LEDGER_IDENTITY_MISSING` even though the wallet directory had a
`cert.pem`/`key.pem` for that exact user id and `FABRIC_WALLET_PASSPHRASE` was set correctly. With temporary logging
added to see the swallowed exception:
```
java.io.IOException: cannot decrypt the wallet key
Caused by: org.bouncycastle.pkcs.PKCSException: unable to read encrypted data: 1.2.840.113549.1.5.13 not available:
  Cannot find any provider supporting AES/CBC/PKCS7Padding
Caused by: org.bouncycastle.operator.OperatorCreationException: 1.2.840.113549.1.5.13 not available: ...
Caused by: java.security.NoSuchAlgorithmException: Cannot find any provider supporting AES/CBC/PKCS7Padding
```

## Diagnosis

1. `1.2.840.113549.1.5.13` is the PBES2 OID (the scheme `openssl pkcs8 -topk8 -v2 aes-256-cbc` produces). Decrypting
   it needs a JCE **`Cipher`** provider that implements it, resolved through `java.security.Security`'s registered
   provider list - a completely different lookup from the Java classpath. `bcpkix-jdk18on`/`bcprov-jdk18on` being on
   the classpath makes their *classes* loadable; it does **not** register BouncyCastle as a JCE **provider**, which
   only `Security.addProvider(new BouncyCastleProvider())` does.
2. `FileWalletIdentityStoreTest` (added alongside this code) did not catch it because its own static initialiser
   registers the provider for the whole test JVM (needed there too, for the same reason) - so the test exercised a
   JVM where the bug could not occur. The gap only showed up running the real application.
3. Unencrypted wallet keys were never affected: `Identities.readPrivateKey` (the fabric-gateway helper, used for both
   the service key and unencrypted wallet keys) does its own parsing without a JCE cipher.

## Fix

`FileWalletIdentityStore` now registers the BouncyCastle provider itself, once, in a static initialiser (`if
(Security.getProvider(...) == null)`, so it never double-registers if something else in the app already did), and the
encrypted-key path pins `.setProvider("BC")` explicitly on both the decryptor builder and `JcaPEMKeyConverter`, rather
than relying on whichever provider happens to be first in the JVM's default list.

## Verification

Re-ran the same live check: `POST /api/evidence` as the collector, wallet key encrypted, now `201`; `qscc
GetTransactionByID` on the returned txId shows the transaction's creator certificate CN is the collector's own user
id, not the shared service identity - full result in `docs/TEST_CHECKLIST.md` Appendix P3-A2.
`FileWalletIdentityStoreTest` passes unchanged (it already covered the encrypted-key case; this fix is what makes the
main application match the test JVM's setup, not a change to the test itself).

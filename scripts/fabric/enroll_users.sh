#!/bin/bash
# A2, Option 1 (docs/A2_IDENTITY_DESIGN.md section 3.4): the operator enrollment script. Reads (id, role) for every
# ENABLED user from PostgreSQL, registers and enrolls each through the least-privilege registrar `be-registrar`
# (scripts/fabric/bootstrap_registrar.sh, run once first), and writes a wallet entry FileWalletIdentityStore reads.
# The running backend never runs this script and never holds a registrar credential.
#
# Run from WSL: BE_REGISTRAR_SECRET=... WALLET_DIR=/path/outside/the/repo bash scripts/fabric/enroll_users.sh
# Idempotent: a user who already has a wallet entry is skipped, unless FORCE=1 (re-enrolls with a fresh certificate;
# the old one is left registered at the CA, not revoked automatically - revoke it yourself if that matters).
set -euo pipefail
: "${BE_REGISTRAR_SECRET:?set BE_REGISTRAR_SECRET (from bootstrap_registrar.sh); never commit this}"
: "${WALLET_DIR:?set WALLET_DIR to a directory OUTSIDE the repo (matches FABRIC_WALLET_DIR the app reads, C-07)}"
FORCE=${FORCE:-0}
# Optional (A2-Q5): if set, every wallet key is written PKCS#8-encrypted with this passphrase, matching
# FABRIC_WALLET_PASSPHRASE (FileWalletIdentityStore decrypts it with BouncyCastle). Unset writes plain keys,
# still fully supported by the Java side - encryption protects the wallet directory at rest, nothing else.
FS=${FSAMPLES:-/home/debop/crime-evidence-mgmt/fabric-samples}
export PATH=$FS/bin:$PATH
CAD=$FS/test-network/organizations/fabric-ca/org1
TLS=$CAD/ca-cert.pem
URL=localhost:7054

mkdir -p "$WALLET_DIR"
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT

echo "### Enrolling be-registrar"
FABRIC_CA_CLIENT_HOME=$W/reg fabric-ca-client enroll -u "https://be-registrar:$BE_REGISTRAR_SECRET@$URL" --caname ca-org1 \
  --tls.certfiles "$TLS" >/dev/null 2>&1
echo "ok"

echo "### Reading enabled users from PostgreSQL"
USERS=$(docker exec be-postgres psql -U blockevidence -d blockevidence -t -A -F',' \
  -c "select id, role, email from users where enabled = true order by role")
if [ -z "$USERS" ]; then
  echo "No enabled users found; is be-postgres up and seeded?" >&2
  exit 1
fi

printf '%-38s %-18s %-32s %-10s %s\n' "USER ID" "ROLE" "EMAIL" "ACTION" "CERT EXPIRES"
while IFS=',' read -r id role email; do
  [ -z "$id" ] && continue
  dest="$WALLET_DIR/$id"
  if [ -f "$dest/cert.pem" ] && [ "$FORCE" != "1" ]; then
    expires=$(openssl x509 -in "$dest/cert.pem" -noout -enddate 2>/dev/null | cut -d= -f2)
    printf '%-38s %-18s %-32s %-10s %s\n' "$id" "$role" "$email" "skipped" "$expires (already enrolled; FORCE=1 to redo)"
    continue
  fi

  SECRET=$(openssl rand -hex 16)
  # The CA remembers a registered identity even after a local wallet is lost (D-060/D-061's same recurring
  # failure mode, now seen for user identities too, not just the registrar). register fails with "already
  # registered" in that case - fall back to resetting the existing identity's secret instead of aborting,
  # since we have no way to know its old secret and don't need to: we're about to enroll fresh anyway.
  if ! FABRIC_CA_CLIENT_HOME=$W/reg fabric-ca-client register --caname ca-org1 \
    --id.name "$id" --id.secret "$SECRET" --id.type client --id.affiliation org1.department1 \
    --id.maxenrollments 1 --id.attrs "role=$role:ecert" --tls.certfiles "$TLS" >"$W/register.log" 2>&1; then
    if grep -q "already registered" "$W/register.log"; then
      FABRIC_CA_CLIENT_HOME=$W/reg fabric-ca-client identity modify "$id" --secret "$SECRET" \
        --maxenrollments -1 --caname ca-org1 --tls.certfiles "$TLS" >/dev/null 2>&1
    else
      cat "$W/register.log" >&2
      exit 1
    fi
  fi

  rm -rf "$W/user"
  FABRIC_CA_CLIENT_HOME=$W/user fabric-ca-client enroll -u "https://$id:$SECRET@$URL" --caname ca-org1 \
    --enrollment.attrs 'role,hf.EnrollmentID' --tls.certfiles "$TLS" >/dev/null 2>&1

  mkdir -p "$dest"
  cp "$W/user/msp/signcerts/"*.pem "$dest/cert.pem"
  # exactly one key file in a fresh enrollment's keystore/ (FabricLedgerService.resolveFile makes the same assumption)
  if [ -n "${WALLET_PASSPHRASE:-}" ]; then
    openssl pkcs8 -topk8 -v2 aes-256-cbc -in "$W/user/msp/keystore/"*_sk -out "$dest/key.pem" -passout "pass:$WALLET_PASSPHRASE"
  else
    cp "$W/user/msp/keystore/"*_sk "$dest/key.pem"
  fi
  chmod 600 "$dest/cert.pem" "$dest/key.pem"
  expires=$(openssl x509 -in "$dest/cert.pem" -noout -enddate 2>/dev/null | cut -d= -f2)
  printf '%-38s %-18s %-32s %-10s %s\n' "$id" "$role" "$email" "enrolled" "$expires"
done <<< "$USERS"

echo
if [ -n "${WALLET_PASSPHRASE:-}" ]; then
  echo "Wallet: $WALLET_DIR (set FABRIC_WALLET_DIR to this path and FABRIC_WALLET_PASSPHRASE to the same passphrase for the app; keys are 0600, PKCS#8-encrypted)."
else
  echo "Wallet: $WALLET_DIR (set FABRIC_WALLET_DIR to this path for the app; keys are 0600, UNENCRYPTED - set WALLET_PASSPHRASE and re-run with FORCE=1 to encrypt them)."
fi

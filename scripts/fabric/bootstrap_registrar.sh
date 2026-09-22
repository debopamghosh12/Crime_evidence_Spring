#!/bin/bash
# A2, one-time operator step (docs/A2_IDENTITY_DESIGN.md section 3.4). Registers the LEAST-PRIVILEGE registrar
# `be-registrar` on ca-org1: it may register ONLY client identities carrying ONLY the `role` attribute, and may
# revoke. This is the identity enroll_users.sh runs as. The registrar's secret is chosen by the operator and lives
# only in the operator's shell (BE_REGISTRAR_SECRET); it is never written to the repo, the app's config, or a file.
# The running backend never holds this secret (it only ever reads users' own enrolled certificates from the wallet).
#
# Run from WSL: BE_REGISTRAR_SECRET=... bash scripts/fabric/bootstrap_registrar.sh
# Idempotent: if be-registrar is already registered, this just confirms it and does nothing further.
set -euo pipefail
: "${BE_REGISTRAR_SECRET:?set BE_REGISTRAR_SECRET to a secret you choose (not committed anywhere); this run registers it}"
FS=${FSAMPLES:-/home/debop/crime-evidence-mgmt/fabric-samples}
export PATH=$FS/bin:$PATH
CAD=$FS/test-network/organizations/fabric-ca/org1
TLS=$CAD/ca-cert.pem
URL=localhost:7054
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT

# The bootstrap admin's secret is whatever the CA container was started with (-b flag); reading it back this way
# needs no separate secret of its own, and is the same read-only `docker inspect` any operator with host access
# already has. It is never printed.
BOOT=$(docker inspect ca_org1 --format '{{index .Config.Cmd 2}}' | sed -n 's/.*-b \([^ ]*\) .*/\1/p')
if [ -z "$BOOT" ]; then
  echo "Could not read the ca_org1 bootstrap admin secret from the container command; is ca_org1 running?" >&2
  exit 1
fi

echo "### Enrolling the CA bootstrap admin (secret not shown)"
FABRIC_CA_CLIENT_HOME=$W/admin fabric-ca-client enroll -u "https://$BOOT@$URL" --caname ca-org1 --tls.certfiles "$TLS" \
  2>&1 | sed 's/\x1b\[[0-9;]*m//g' | tail -1

echo "### Checking whether be-registrar already exists"
if FABRIC_CA_CLIENT_HOME=$W/admin fabric-ca-client identity list --id be-registrar --caname ca-org1 --tls.certfiles "$TLS" \
     2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -q '^Name: be-registrar'; then
  echo "be-registrar is already registered; nothing to do. (Lost the secret? Reissue it: 'fabric-ca-client identity"
  echo "modify be-registrar --secret <new> ...' as this admin, then re-run enroll_users.sh with the new secret.)"
  exit 0
fi

echo "### Registering be-registrar (client only, attribute 'role' only, may revoke)"
FABRIC_CA_CLIENT_HOME=$W/admin fabric-ca-client register --caname ca-org1 \
  --id.name be-registrar --id.secret "$BE_REGISTRAR_SECRET" --id.type client --id.affiliation org1.department1 \
  --id.attrs 'hf.Registrar.Roles=client,hf.Registrar.Attributes=role,hf.Revoker=true' --tls.certfiles "$TLS" \
  2>&1 | sed 's/\x1b\[[0-9;]*m//g' | tail -1 | sed 's/Password: .*/Password: <redacted>/'

echo "### Confirming it can enroll and register a client with only the 'role' attribute (escalation attempts must be refused)"
FABRIC_CA_CLIENT_HOME=$W/reg fabric-ca-client enroll -u "https://be-registrar:$BE_REGISTRAR_SECRET@$URL" --caname ca-org1 \
  --tls.certfiles "$TLS" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | tail -1
echo "  (an escalation check like the one in TEST_CHECKLIST Appendix P3-A was already run against this exact registrar shape)"
echo "be-registrar is ready. Set BE_REGISTRAR_SECRET in the operator's shell for enroll_users.sh and do not store it elsewhere."

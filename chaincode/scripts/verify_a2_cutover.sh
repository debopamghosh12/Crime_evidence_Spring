#!/bin/bash
# A2 verification plan item 2 (docs/A2_IDENTITY_DESIGN.md section 6): drives the REAL, now-enforcing chaincode
# (evidence v1.3+) through the peer CLI with different REAL Fabric CA identities, to directly demonstrate that role
# forgery by the backend's own caller no longer works. Uses be-registrar (BE_REGISTRAR_SECRET) to enroll two
# throwaway identities; cleans them up at the end. Run from WSL.
# No `-e`: several invokes below are DELIBERATELY expected to be refused (non-zero exit), and the script must
# keep going to print and check every case, not abort on the first expected failure.
set -uo pipefail
: "${BE_REGISTRAR_SECRET:?set BE_REGISTRAR_SECRET}"
FS=${FSAMPLES:-/home/debop/crime-evidence-mgmt/fabric-samples}
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/fab_env.sh"
CAD=$FS/test-network/organizations/fabric-ca/org1
CATLS=$CAD/ca-cert.pem
CAURL=localhost:7054
CH=crimechannel; CC=evidence
ORD="-o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --tls --cafile $ORDERER_CA"
PEERS="--peerAddresses localhost:7051 --tlsRootCertFiles $ORG1_TLS --peerAddresses localhost:9051 --tlsRootCertFiles $ORG2_TLS"
W=$(mktemp -d)
trap 'REG=$W/reg; [ -d "$REG" ] && [ -n "${JUDGE_ID:-}" ] && FABRIC_CA_CLIENT_HOME=$REG fabric-ca-client revoke -e "$JUDGE_ID" --caname ca-org1 --tls.certfiles "$CATLS" >/dev/null 2>&1; [ -d "$REG" ] && [ -n "${COLL_ID:-}" ] && FABRIC_CA_CLIENT_HOME=$REG fabric-ca-client revoke -e "$COLL_ID" --caname ca-org1 --tls.certfiles "$CATLS" >/dev/null 2>&1; rm -rf "$W"' EXIT

echo "### Enrolling be-registrar and two throwaway identities (revoked at the end)"
FABRIC_CA_CLIENT_HOME=$W/reg fabric-ca-client enroll -u "https://be-registrar:$BE_REGISTRAR_SECRET@$CAURL" --caname ca-org1 --tls.certfiles "$CATLS" >/dev/null 2>&1

JUDGE_ID=$(cat /proc/sys/kernel/random/uuid); JUDGE_SECRET=$(openssl rand -hex 12)
FABRIC_CA_CLIENT_HOME=$W/reg fabric-ca-client register --caname ca-org1 --id.name "$JUDGE_ID" --id.secret "$JUDGE_SECRET" \
  --id.type client --id.affiliation org1.department1 --id.maxenrollments 1 --id.attrs 'role=JUDGE:ecert' --tls.certfiles "$CATLS" >/dev/null 2>&1
FABRIC_CA_CLIENT_HOME=$W/judge fabric-ca-client enroll -u "https://$JUDGE_ID:$JUDGE_SECRET@$CAURL" --caname ca-org1 \
  --enrollment.attrs 'role,hf.EnrollmentID' --tls.certfiles "$CATLS" >/dev/null 2>&1

COLL_ID=$(cat /proc/sys/kernel/random/uuid); COLL_SECRET=$(openssl rand -hex 12)
FABRIC_CA_CLIENT_HOME=$W/reg fabric-ca-client register --caname ca-org1 --id.name "$COLL_ID" --id.secret "$COLL_SECRET" \
  --id.type client --id.affiliation org1.department1 --id.maxenrollments 1 --id.attrs 'role=COLLECTOR:ecert' --tls.certfiles "$CATLS" >/dev/null 2>&1
FABRIC_CA_CLIENT_HOME=$W/coll fabric-ca-client enroll -u "https://$COLL_ID:$COLL_SECRET@$CAURL" --caname ca-org1 \
  --enrollment.attrs 'role,hf.EnrollmentID' --tls.certfiles "$CATLS" >/dev/null 2>&1
echo "judge identity   = $JUDGE_ID"
echo "collector identity = $COLL_ID"

# fabric-ca-client enroll does not write config.yaml (NodeOUs); the peer CLI's local MSP loader needs it (or
# admincerts) or it refuses to start with "administrators must be declared when no admin ou classification is set".
# The org's own MSP already has a working one; only the CA cert FILENAME inside it must match this MSP's cacerts/.
writeNodeOUs() {
  local mspdir=$1
  local cacert
  cacert=$(basename "$(ls "$mspdir/cacerts/"*.pem | head -1)")
  cat > "$mspdir/config.yaml" <<YAML
NodeOUs:
  Enable: true
  ClientOUIdentifier:
    Certificate: cacerts/$cacert
    OrganizationalUnitIdentifier: client
  PeerOUIdentifier:
    Certificate: cacerts/$cacert
    OrganizationalUnitIdentifier: peer
  AdminOUIdentifier:
    Certificate: cacerts/$cacert
    OrganizationalUnitIdentifier: admin
  OrdererOUIdentifier:
    Certificate: cacerts/$cacert
    OrganizationalUnitIdentifier: orderer
YAML
}
writeNodeOUs "$W/judge/msp"
writeNodeOUs "$W/coll/msp"

asIdentity() { # asIdentity <mspdir>
  export CORE_PEER_LOCALMSPID=Org1MSP CORE_PEER_TLS_ROOTCERT_FILE=$ORG1_TLS CORE_PEER_ADDRESS=localhost:7051 CORE_PEER_MSPCONFIGPATH="$1/msp"
}

echo
echo "### 1. User1 (pre-A2 identity, no role certificate attribute) tries to write: must be refused"
asIdentity "$FS/test-network/organizations/peerOrganizations/org1.example.com/users/User1@org1.example.com"
EV1=EV-$(cat /proc/sys/kernel/random/uuid)
peer chaincode invoke $ORD -C $CH -n $CC $PEERS --waitForEvent \
  -c "{\"function\":\"CreateEvidence\",\"Args\":[\"$EV1\",\"FAB-A2-CUTOVER\",\"PHYSICAL\",\"bkjgrhtopqegexdvpbv6vtr5sqyrqpxj4662kls37mou7jag2ks66\",\"$(printf 'a%.0s' $(seq 64))\",\"\",\"\",\"\",\"11111111-1111-4111-8111-111111111111\",\"COLLECTOR\"]}" \
  2>&1 | sed 's/\x1b\[[0-9;]*m//g' | tail -1

echo
echo "### 2. A JUDGE certificate claiming COLLECTOR in the argument: must be refused"
asIdentity "$W/judge"
EV2=EV-$(cat /proc/sys/kernel/random/uuid)
peer chaincode invoke $ORD -C $CH -n $CC $PEERS --waitForEvent \
  -c "{\"function\":\"CreateEvidence\",\"Args\":[\"$EV2\",\"FAB-A2-CUTOVER\",\"PHYSICAL\",\"bkjgrhtopqegexdvpbv6vtr5sqyrqpxj4662kls37mou7jag2ks66\",\"$(printf 'a%.0s' $(seq 64))\",\"\",\"\",\"\",\"$JUDGE_ID\",\"COLLECTOR\"]}" \
  2>&1 | sed 's/\x1b\[[0-9;]*m//g' | tail -1

echo
echo "### 3. A COLLECTOR certificate claiming JUDGE in the argument (ApproveDisposal): must be refused"
asIdentity "$W/coll"
peer chaincode invoke $ORD -C $CH -n $CC $PEERS --waitForEvent \
  -c "{\"function\":\"ApproveDisposal\",\"Args\":[\"$EV1\",\"1\",\"ok\",\"$COLL_ID\",\"JUDGE\"]}" \
  2>&1 | sed 's/\x1b\[[0-9;]*m//g' | tail -1

echo
echo "### 4. Positive control: the SAME collector certificate, correctly claiming COLLECTOR: must succeed"
asIdentity "$W/coll"
EV4=EV-$(cat /proc/sys/kernel/random/uuid)
peer chaincode invoke $ORD -C $CH -n $CC $PEERS --waitForEvent \
  -c "{\"function\":\"CreateEvidence\",\"Args\":[\"$EV4\",\"FAB-A2-CUTOVER\",\"PHYSICAL\",\"bkjgrhtopqegexdvpbv6vtr5sqyrqpxj4662kls37mou7jag2ks66\",\"$(printf 'a%.0s' $(seq 64))\",\"\",\"\",\"\",\"$COLL_ID\",\"COLLECTOR\"]}" \
  2>&1 | sed 's/\x1b\[[0-9;]*m//g' | tail -1
peer chaincode query -C $CH -n $CC -c "{\"function\":\"GetEvidence\",\"Args\":[\"$EV4\"]}" 2>&1 | sed 's/\x1b\[[0-9;]*m//g'

echo
echo "### 5. qscc: the creator of that write's transaction is the collector's OWN certificate (CN = its enrollment id)"
TXID=$(peer chaincode query -C $CH -n $CC -c "{\"function\":\"GetHistory\",\"Args\":[\"$EV4\"]}" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['txId'])")
peer chaincode query -C $CH -n qscc -c "{\"Args\":[\"GetTransactionByID\",\"$CH\",\"$TXID\"]}" 2>&1 | strings | grep -o "CN=[0-9a-f-]*" | head -1
echo "(expected to equal: CN=$COLL_ID)"

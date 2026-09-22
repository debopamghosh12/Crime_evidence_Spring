#!/bin/bash
# G3 live verification (docs/G3_SYNC_DESIGN.md section 10): writes directly to the chaincode, bypassing Spring
# entirely, so it can be run while the whole application (and its event listener) is stopped. Confirms the
# listener replays missed blocks on restart rather than silently dropping them. Run from WSL.
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
trap 'REG=$W/reg; [ -d "$REG" ] && FABRIC_CA_CLIENT_HOME=$REG fabric-ca-client revoke -e "$COLL_ID" --caname ca-org1 --tls.certfiles "$CATLS" >/dev/null 2>&1; rm -rf "$W"' EXIT

echo "### Enrolling a throwaway COLLECTOR identity (revoked at the end)"
FABRIC_CA_CLIENT_HOME=$W/reg fabric-ca-client enroll -u "https://be-registrar:$BE_REGISTRAR_SECRET@$CAURL" --caname ca-org1 --tls.certfiles "$CATLS" >/dev/null 2>&1
COLL_ID=$(cat /proc/sys/kernel/random/uuid); COLL_SECRET=$(openssl rand -hex 12)
FABRIC_CA_CLIENT_HOME=$W/reg fabric-ca-client register --caname ca-org1 --id.name "$COLL_ID" --id.secret "$COLL_SECRET" \
  --id.type client --id.affiliation org1.department1 --id.maxenrollments 1 --id.attrs 'role=COLLECTOR:ecert' --tls.certfiles "$CATLS" >/dev/null 2>&1
FABRIC_CA_CLIENT_HOME=$W/coll fabric-ca-client enroll -u "https://$COLL_ID:$COLL_SECRET@$CAURL" --caname ca-org1 \
  --enrollment.attrs 'role,hf.EnrollmentID' --tls.certfiles "$CATLS" >/dev/null 2>&1
cacert=$(basename "$(ls "$W/coll/msp/cacerts/"*.pem | head -1)")
cat > "$W/coll/msp/config.yaml" <<YAML
NodeOUs:
  Enable: true
  ClientOUIdentifier: {Certificate: cacerts/$cacert, OrganizationalUnitIdentifier: client}
  PeerOUIdentifier: {Certificate: cacerts/$cacert, OrganizationalUnitIdentifier: peer}
  AdminOUIdentifier: {Certificate: cacerts/$cacert, OrganizationalUnitIdentifier: admin}
  OrdererOUIdentifier: {Certificate: cacerts/$cacert, OrganizationalUnitIdentifier: orderer}
YAML
export CORE_PEER_LOCALMSPID=Org1MSP CORE_PEER_TLS_ROOTCERT_FILE=$ORG1_TLS CORE_PEER_ADDRESS=localhost:7051 CORE_PEER_MSPCONFIGPATH=$W/coll/msp

for n in 1 2; do
  EV=EV-$(cat /proc/sys/kernel/random/uuid)
  echo "### Writing $EV directly to the chaincode (Spring is stopped; this is exactly what it would miss)"
  peer chaincode invoke $ORD -C $CH -n $CC $PEERS --waitForEvent \
    -c "{\"function\":\"CreateEvidence\",\"Args\":[\"$EV\",\"FAB-G3-REPLAY\",\"PHYSICAL\",\"bkjgrhtopqegexdvpbv6vtr5sqyrqpxj4662kls37mou7jag2ks66\",\"$(printf 'a%.0s' $(seq 64))\",\"\",\"\",\"\",\"$COLL_ID\",\"COLLECTOR\"]}" \
    2>&1 | sed 's/\x1b\[[0-9;]*m//g' | tail -1
  echo "$EV" >> /tmp/g3_replay_ids.txt
done
echo "written ids:"; cat /tmp/g3_replay_ids.txt

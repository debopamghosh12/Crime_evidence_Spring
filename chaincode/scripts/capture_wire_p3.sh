#!/bin/bash
# Captures REAL chaincode output for the Phase 3 (custody transfer) wire-format tests. Additive: the Phase 2 fixtures written by
# capture_wire.sh (chaincode v1.1 era, no "transfer" field) are kept on purpose as the legacy-record fixtures.
SPW="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # this scripts directory (inside WSL)
. "$SPW/fab_env.sh"; org1
CH=crimechannel; CC=evidence
ORD="-o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --tls --cafile $ORDERER_CA"
PEERS="--peerAddresses localhost:7051 --tlsRootCertFiles $ORG1_TLS --peerAddresses localhost:9051 --tlsRootCertFiles $ORG2_TLS"
OUT=/tmp/wire3; rm -rf $OUT; mkdir -p $OUT
rcid() { printf 'b%s' "$(head -c 40 /dev/urandom | base32 | tr 'A-Z' 'a-z' | tr -d '=\n' | head -c 52)"; }
SHA=$(printf 'a%.0s' $(seq 64))
COLLECTOR=11111111-1111-4111-8111-111111111111
ANALYST=22222222-2222-4222-8222-222222222222
PROSECUTOR=33333333-3333-4333-8333-333333333333
EV=EV-$(cat /proc/sys/kernel/random/uuid)

inv() { peer chaincode invoke $ORD -C $CH -n $CC $PEERS --waitForEvent -c "$1" 2>&1 | sed 's/\x1b\[[0-9;]*m//g'; }
payload() { grep -o 'payload:".*"' | sed 's/^payload:"//; s/" *$//; s/\\"/"/g'; }
q() { peer chaincode query -C $CH -n $CC -c "$2" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' > "$OUT/$1"; }

inv "{\"function\":\"CreateEvidence\",\"Args\":[\"$EV\",\"FAB-WIRE-P3\",\"PHYSICAL\",\"$(rcid)\",\"$SHA\",\"\",\"\",\"\",\"$COLLECTOR\",\"COLLECTOR\"]}" > /dev/null
q p3-get-new.json "{\"function\":\"GetEvidence\",\"Args\":[\"$EV\"]}"

inv "{\"function\":\"InitiateTransfer\",\"Args\":[\"$EV\",\"1\",\"$ANALYST\",\"FORENSIC_ANALYST\",\"forensic analysis\",\"sealed bag 7\",\"$COLLECTOR\",\"COLLECTOR\"]}" | payload > $OUT/p3-tx-result-initiate.json
q p3-get-pending.json "{\"function\":\"GetEvidence\",\"Args\":[\"$EV\"]}"
q p3-find-pending.json "{\"function\":\"FindPendingTransfers\",\"Args\":[\"$ANALYST\"]}"
q p3-find-pending-none.json "{\"function\":\"FindPendingTransfers\",\"Args\":[\"$PROSECUTOR\"]}"

# refused calls: real error text as the peer prints it
inv "{\"function\":\"AcceptTransfer\",\"Args\":[\"$EV\",\"2\",\"n\",\"$PROSECUTOR\",\"PROSECUTOR\"]}" > $OUT/p3-error-forbidden-role.txt
inv "{\"function\":\"InitiateTransfer\",\"Args\":[\"$EV\",\"2\",\"$PROSECUTOR\",\"PROSECUTOR\",\"r\",\"\",\"$COLLECTOR\",\"COLLECTOR\"]}" > $OUT/p3-error-invalid-state.txt

inv "{\"function\":\"AcceptTransfer\",\"Args\":[\"$EV\",\"2\",\"received intact\",\"$ANALYST\",\"FORENSIC_ANALYST\"]}" | payload > $OUT/p3-tx-result-accept.json
q p3-get-accepted.json "{\"function\":\"GetEvidence\",\"Args\":[\"$EV\"]}"
q p3-find-pending-after.json "{\"function\":\"FindPendingTransfers\",\"Args\":[\"$ANALYST\"]}"

inv "{\"function\":\"InitiateTransfer\",\"Args\":[\"$EV\",\"3\",\"$PROSECUTOR\",\"PROSECUTOR\",\"court filing\",\"\",\"$ANALYST\",\"FORENSIC_ANALYST\"]}" > /dev/null
inv "{\"function\":\"RejectTransfer\",\"Args\":[\"$EV\",\"4\",\"not ready\",\"$PROSECUTOR\",\"PROSECUTOR\"]}" > /dev/null
q p3-history.json "{\"function\":\"GetHistory\",\"Args\":[\"$EV\"]}"

ls -la $OUT | tail -n +2 | awk '{print $5, $9}'
mkdir -p "$SPW/../../src/test/resources/fabric" && cp $OUT/* "$SPW/../../src/test/resources/fabric/" && echo copied

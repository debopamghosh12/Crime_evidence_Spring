#!/bin/bash
# Captures REAL chaincode output as fixtures for the Java wire-format tests.
SPW="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # this scripts directory (inside WSL)
. "$SPW/fab_env.sh"; org1
CH=crimechannel; CC=evidence
ORD="-o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --tls --cafile $ORDERER_CA"
PEERS="--peerAddresses localhost:7051 --tlsRootCertFiles $ORG1_TLS --peerAddresses localhost:9051 --tlsRootCertFiles $ORG2_TLS"
OUT=/tmp/wire; rm -rf $OUT; mkdir -p $OUT
rcid() { printf 'b%s' "$(head -c 40 /dev/urandom | base32 | tr 'A-Z' 'a-z' | tr -d '=\n' | head -c 52)"; }
SHA=$(printf 'a%.0s' $(seq 64)); COLLECTOR=11111111-1111-4111-8111-111111111111
DIGITAL=$(cat /tmp/direct_id.txt)                       # created, updated, status-changed, disposed by cc_direct.sh
PHYS=EV-$(cat /proc/sys/kernel/random/uuid); M=$(rcid)

# a submit whose payload is the TxResult JSON (the value FabricLedgerService parses)
peer chaincode invoke $ORD -C $CH -n $CC $PEERS --waitForEvent \
  -c "{\"function\":\"CreateEvidence\",\"Args\":[\"$PHYS\",\"FAB-WIRE\",\"PHYSICAL\",\"$M\",\"$SHA\",\"\",\"\",\"\",\"$COLLECTOR\",\"COLLECTOR\"]}" 2>&1 \
  | sed 's/\x1b\[[0-9;]*m//g' | grep -o 'payload:".*"' | sed 's/^payload:"//; s/" *$//; s/\\"/"/g' > $OUT/tx-result.json

q() { peer chaincode query -C $CH -n $CC -c "$2" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' > "$OUT/$1.json"; }
q get-physical  "{\"function\":\"GetEvidence\",\"Args\":[\"$PHYS\"]}"
q get-digital-disposed "{\"function\":\"GetEvidence\",\"Args\":[\"$DIGITAL\"]}"
q history-digital "{\"function\":\"GetHistory\",\"Args\":[\"$DIGITAL\"]}"
FILECID=$(python3 -c "import json;print(json.load(open('$OUT/get-digital-disposed.json'))['fileCid'])")
q find-by-cid "{\"function\":\"FindByCid\",\"Args\":[\"$FILECID\"]}"
q find-by-cid-none "{\"function\":\"FindByCid\",\"Args\":[\"$(rcid)\"]}"
peer chaincode query -C $CH -n $CC -c "{\"function\":\"GetEvidence\",\"Args\":[\"EV-00000000-0000-4000-8000-000000000000\"]}" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' > $OUT/error-not-found.txt
peer chaincode invoke $ORD -C $CH -n $CC $PEERS --waitForEvent -c "{\"function\":\"UpdateEvidence\",\"Args\":[\"$PHYS\",\"9\",\"$(rcid)\",\"$SHA\",\"stale\",\"$COLLECTOR\",\"COLLECTOR\"]}" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' > $OUT/error-version-conflict.txt
ls -la $OUT | tail -n +2 | awk '{print $5, $9}'
mkdir -p "$SPW/../../src/test/resources/fabric" && cp $OUT/* "$SPW/../../src/test/resources/fabric/" && echo copied

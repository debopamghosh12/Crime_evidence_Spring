#!/bin/bash
# Drives the REAL chaincode through the peer CLI, bypassing Spring, to prove the chaincode's own checks.
SPW="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # this scripts directory (inside WSL)
. "$SPW/fab_env.sh"; org1
CH=crimechannel; CC=evidence
ORD="-o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --tls --cafile $ORDERER_CA"
PEERS="--peerAddresses localhost:7051 --tlsRootCertFiles $ORG1_TLS --peerAddresses localhost:9051 --tlsRootCertFiles $ORG2_TLS"

COLLECTOR=11111111-1111-4111-8111-111111111111
JUDGE=44444444-4444-4444-8444-444444444444
ADMIN=66666666-6666-4666-8666-666666666666
rcid() { printf 'b%s' "$(head -c 40 /dev/urandom | base32 | tr 'A-Z' 'a-z' | tr -d '=\n' | head -c 52)"; }
SHA=$(printf 'a%.0s' $(seq 64))

# inv <label> <json-args>  (submit: endorsed by BOTH orgs, ordered, committed)
inv() { echo; echo "### $1"; shift
  out=$(peer chaincode invoke $ORD -C $CH -n $CC $PEERS --waitForEvent -c "$1" 2>&1 | sed 's/\x1b\[[0-9;]*m//g')
  echo "$out" | grep -oE 'status:[0-9]+ (payload|message):"[^"]*"|Error:.*' | sed 's/\\"/"/g' | cut -c1-330 | head -3; }
# qry <label> <json-args>  (evaluate: one peer, nothing ordered)
qry() { echo; echo "### $1"; shift
  peer chaincode query -C $CH -n $CC -c "$1" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | cut -c1-700 | head -4; }

ID=EV-$(cat /proc/sys/kernel/random/uuid); M1=$(rcid); M2=$(rcid); F1=$(rcid)
echo "evidence id under test: $ID"

inv "CreateEvidence DIGITAL as COLLECTOR (both orgs endorse)" \
  "{\"function\":\"CreateEvidence\",\"Args\":[\"$ID\",\"FAB-DIRECT\",\"DIGITAL\",\"$M1\",\"$SHA\",\"$F1\",\"$SHA\",\"1234\",\"$COLLECTOR\",\"COLLECTOR\"]}"
qry "GetEvidence" "{\"function\":\"GetEvidence\",\"Args\":[\"$ID\"]}"
inv "CreateEvidence as JUDGE (role table: must be refused BY THE CHAINCODE)" \
  "{\"function\":\"CreateEvidence\",\"Args\":[\"EV-$(cat /proc/sys/kernel/random/uuid)\",\"FAB-DIRECT\",\"PHYSICAL\",\"$M1\",\"$SHA\",\"\",\"\",\"\",\"$JUDGE\",\"JUDGE\"]}"
inv "CreateEvidence as ADMIN (Admin never writes evidence)" \
  "{\"function\":\"CreateEvidence\",\"Args\":[\"EV-$(cat /proc/sys/kernel/random/uuid)\",\"FAB-DIRECT\",\"PHYSICAL\",\"$M1\",\"$SHA\",\"\",\"\",\"\",\"$ADMIN\",\"ADMIN\"]}"
inv "Create the same id again" \
  "{\"function\":\"CreateEvidence\",\"Args\":[\"$ID\",\"FAB-DIRECT\",\"DIGITAL\",\"$M2\",\"$SHA\",\"$F1\",\"$SHA\",\"1234\",\"$COLLECTOR\",\"COLLECTOR\"]}"
inv "UpdateEvidence with a STALE expectedVersion (record is at 1, caller says 7)" \
  "{\"function\":\"UpdateEvidence\",\"Args\":[\"$ID\",\"7\",\"$M2\",\"$SHA\",\"stale\",\"$COLLECTOR\",\"COLLECTOR\"]}"
inv "UpdateEvidence v1 -> v2 (metadata pointer only)" \
  "{\"function\":\"UpdateEvidence\",\"Args\":[\"$ID\",\"1\",\"$M2\",\"$SHA\",\"fixed a typo\",\"$COLLECTOR\",\"COLLECTOR\"]}"
inv "UpdateStatus straight to ARCHIVED (skips PROCESSING and ANALYZED)" \
  "{\"function\":\"UpdateStatus\",\"Args\":[\"$ID\",\"2\",\"ARCHIVED\",\"skip\",\"$COLLECTOR\",\"COLLECTOR\"]}"
inv "UpdateStatus to DISPOSED (only an approved disposal may do that)" \
  "{\"function\":\"UpdateStatus\",\"Args\":[\"$ID\",\"2\",\"DISPOSED\",\"shortcut\",\"$COLLECTOR\",\"COLLECTOR\"]}"
inv "UpdateStatus COLLECTED -> PROCESSING (legal)" \
  "{\"function\":\"UpdateStatus\",\"Args\":[\"$ID\",\"2\",\"PROCESSING\",\"start analysis\",\"$COLLECTOR\",\"COLLECTOR\"]}"
inv "There is no delete: invoking DeleteEvidence" "{\"function\":\"DeleteEvidence\",\"Args\":[\"$ID\"]}"
inv "RequestDisposal by COLLECTOR" "{\"function\":\"RequestDisposal\",\"Args\":[\"$ID\",\"3\",\"case closed\",\"$COLLECTOR\",\"COLLECTOR\"]}"
inv "ApproveDisposal by COLLECTOR (refused: only a JUDGE)" "{\"function\":\"ApproveDisposal\",\"Args\":[\"$ID\",\"4\",\"ok\",\"$COLLECTOR\",\"COLLECTOR\"]}"
inv "ApproveDisposal by JUDGE with a stale version (3, record at 4)" "{\"function\":\"ApproveDisposal\",\"Args\":[\"$ID\",\"3\",\"ok\",\"$JUDGE\",\"JUDGE\"]}"
inv "ApproveDisposal by JUDGE with the reviewed version" "{\"function\":\"ApproveDisposal\",\"Args\":[\"$ID\",\"4\",\"order verified\",\"$JUDGE\",\"JUDGE\"]}"
inv "UpdateEvidence after DISPOSED (record is frozen)" \
  "{\"function\":\"UpdateEvidence\",\"Args\":[\"$ID\",\"5\",\"$(rcid)\",\"$SHA\",\"too late\",\"$COLLECTOR\",\"COLLECTOR\"]}"
qry "GetEvidence after disposal (record still on the ledger)" "{\"function\":\"GetEvidence\",\"Args\":[\"$ID\"]}"
qry "FindByCid (the FILE cid)" "{\"function\":\"FindByCid\",\"Args\":[\"$F1\"]}"
qry "FindByCid (the ORIGINAL metadata cid, still indexed after the update)" "{\"function\":\"FindByCid\",\"Args\":[\"$M1\"]}"
echo; echo "### GetHistory (from the peer's history index): version, action, txId, ledger timestamp"
peer chaincode query -C $CH -n $CC -c "{\"function\":\"GetHistory\",\"Args\":[\"$ID\"]}" 2>&1 | python3 -c "
import sys,json
for e in json.load(sys.stdin):
    r=e['record']; print('   v%d %-19s tx=%s..  at=%s  by=%s  metadataCid=%s..' % (r['version'], r['lastAction'], e['txId'][:16], e['timestamp'], r['updatedByRole'], r['metadataCid'][:10]))"
echo "$ID" > /tmp/direct_id.txt

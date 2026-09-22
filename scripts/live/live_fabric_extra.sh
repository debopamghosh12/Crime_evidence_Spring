#!/bin/bash
# Fabric-specific checks that the in-memory ledger cannot demonstrate. Run after live_phase2.sh.
export MSYS_NO_PATHCONV=1
SP="${WORKDIR:?set WORKDIR to a scratch directory OUTSIDE the repo that contains env.sh (secrets and Fabric paths)}"
SPW="${FAB_SCRIPTS_WSL:?set FAB_SCRIPTS_WSL to the WSL path of chaincode/scripts}"
. "$SP/env.sh"; cd "$SP"
B=http://localhost:8080; PW="$BLOCKEVIDENCE_DEV_SEED_PASSWORD"
tok() { curl -s -X POST $B/api/auth/login -H 'Content-Type: application/json' -d "{\"email\":\"$1@blockevidence.local\",\"password\":\"$PW\"}" | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])"; }
pick() { python -c "
import sys,json
d=json.load(sys.stdin)
def get(o,p):
    for k in p.split('.'):
        o=o.get(k) if isinstance(o,dict) else None
    return o
for p in sys.argv[1:]: print('   %-22s %s' % (p, get(d,p)))
" "$@"; }
status() { curl -s -o body.json -w '%{http_code}' "$@"; }
hdr() { echo; echo "### $*"; }
TC=$(tok collector); TA=$(tok forensic-analyst); TAU=$(tok auditor); TAD=$(tok admin)

hdr "F1. Every txId the API reports is a real transaction on the peers' ledger (qscc GetTransactionByID)"
ID=$(curl -s -X POST $B/api/evidence -H "Authorization: Bearer $TC" -F 'metadata={"caseId":"FAB-LIVE-4","type":"PHYSICAL","description":"tx existence probe"};type=application/json' | python -c "import sys,json;print(json.load(sys.stdin)['evidenceId'])")
curl -s $B/api/evidence/$ID/history -H "Authorization: Bearer $TAU" -o body.json
TX=$(python -c "import json;print(json.load(open('body.json'))[0]['txId'])"); API_TS=$(python -c "import json;print(json.load(open('body.json'))[0]['timestamp'])")
echo "   evidence $ID  API says: txId=$TX  ledger timestamp=$API_TS"
wsl -d Ubuntu -- bash -c ". \"$SPW/fab_env.sh\"; org1; peer chaincode query -C crimechannel -n qscc -c '{\"Args\":[\"GetTransactionByID\",\"crimechannel\",\"$TX\"]}' 2>&1 | strings | grep -E 'EV-|evidence|CreateEvidence|Org1MSP|Org2MSP' | sort -u | head -6" | tr -d '\0\r' | sed 's/^/   qscc found -> /'
wsl -d Ubuntu -- bash -c ". \"$SPW/fab_env.sh\"; org1; peer chaincode query -C crimechannel -n qscc -c '{\"Args\":[\"GetTransactionByID\",\"crimechannel\",\"0000000000000000000000000000000000000000000000000000000000000000\"]}' 2>&1 | tail -1 | cut -c1-160" | tr -d '\0\r' | sed 's/^/   a made-up txId -> /'

hdr "F2. Concurrent updates to ONE record with the same expectedVersion (Fabric MVCC / version check): exactly one may win"
ID2=$(curl -s -X POST $B/api/evidence -H "Authorization: Bearer $TC" -F 'metadata={"caseId":"FAB-LIVE-4","type":"PHYSICAL","description":"race target"};type=application/json' | python -c "import sys,json;print(json.load(sys.stdin)['evidenceId'])")
for r in 1 2 3; do
  ID2=$(curl -s -X POST $B/api/evidence -H "Authorization: Bearer $TC" -F 'metadata={"caseId":"FAB-LIVE-4","type":"PHYSICAL","description":"race target"};type=application/json' | python -c "import sys,json;print(json.load(sys.stdin)['evidenceId'])")
  for i in 1 2 3 4; do curl -s -o race_$i.json -w "%{http_code}\n" -X PUT $B/api/evidence/$ID2 -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' -d "{\"expectedVersion\":1,\"reason\":\"racer $i\",\"notes\":\"writer $i\"}" > race_$i.code & done; wait
  echo "   round $r: statuses -> $(cat race_[1-4].code | sort | uniq -c | tr '\n' ' ')   losers' error: $(cat race_*.json | python -c "
import sys,json
seen=set()
for chunk in sys.stdin.read().replace('}{','}\n{').splitlines():
    try: d=json.loads(chunk)
    except Exception: continue
    if 'error' in d: seen.add(d['error'])
print(sorted(seen))")"
  curl -s $B/api/evidence/$ID2/history -H "Authorization: Bearer $TAU" | python -c "import sys,json;h=json.load(sys.stdin);print('            history length after the race:',len(h),' versions:',[e['version'] for e in h])"
done; rm -f race_*.json race_*.code

hdr "F3. Ledger outage: stop the Org1 peer the backend talks to"
docker stop peer0.org1.example.com >/dev/null; sleep 3
echo "   GET evidence      -> $(status $B/api/evidence/$ID -H "Authorization: Bearer $TAU")  $(python -c "import json;d=json.load(open('body.json'));print(d['error'],'|',d['message'])")"
echo "   register          -> $(status -X POST $B/api/evidence -H "Authorization: Bearer $TC" -F 'metadata={"caseId":"FAB-LIVE-4","type":"PHYSICAL","description":"during outage"};type=application/json')  $(python -c "import json;d=json.load(open('body.json'));print(d['error'])")"
status $B/actuator/health -H "Authorization: Bearer $TAD" >/dev/null; python -c "
import json;d=json.load(open('body.json'));print('   health overall    ->',d['status'],'| ledger:',d['components']['ledger'])"
echo "   /api/auth/me still works (Postgres, not the ledger) -> $(status $B/api/auth/me -H "Authorization: Bearer $TC")"
docker start peer0.org1.example.com >/dev/null
for i in $(seq 1 60); do c=$(status $B/api/evidence/$ID -H "Authorization: Bearer $TAU"); [ "$c" = "200" ] && break; sleep 2; done
echo "   after restarting the peer: GET evidence -> $c (recovered after ~$((i*2)) s, no app restart)"
echo "$ID" > "$SP/persist_id.txt"

#!/bin/bash
# Live verification of Phase 2 against the running app: real Postgres, real (offline) Kubo,
# in-memory REFERENCE ledger (memory-ledger profile). Not a Fabric run.
SP="${WORKDIR:?set WORKDIR to a scratch directory OUTSIDE the repo that contains env.sh (secrets and Fabric paths)}"
. "$SP/env.sh"; cd "$SP"
export MSYS_NO_PATHCONV=1
B=http://localhost:8080
PW="$BLOCKEVIDENCE_DEV_SEED_PASSWORD"

tok() { curl -s -X POST $B/api/auth/login -H 'Content-Type: application/json' -d "{\"email\":\"$1@blockevidence.local\",\"password\":\"$PW\"}" | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])"; }
# pick() prints selected top-level fields (dotted paths) of the JSON on stdin
pick() { python -c "
import sys,json
d=json.load(sys.stdin)
def get(o,p):
    for k in p.split('.'):
        o=o.get(k) if isinstance(o,dict) else None
    return o
for p in sys.argv[1:]: print('   %-26s %s' % (p, get(d,p)))
" "$@"; }
status() { curl -s -o body.json -w '%{http_code}' "$@"; }
hdr() { echo; echo "### $*"; }
say() { echo "   -> $*"; }
wait_ipfs() { for i in $(seq 1 60); do docker inspect -f '{{.State.Health.Status}}' be-ipfs 2>/dev/null | grep -q healthy && break; sleep 1; done; }

TC=$(tok collector); TA=$(tok forensic-analyst); TP=$(tok prosecutor); TJ=$(tok judge); TAD=$(tok admin); TAU=$(tok auditor)
COLLECTOR_ID=$(curl -s $B/api/auth/me -H "Authorization: Bearer $TC" | python -c "import sys,json;print(json.load(sys.stdin)['id'])")
echo "collector user id (from /api/auth/me): $COLLECTOR_ID"

reg() { # reg <token> <metadata-json> [file] [ctype]
  if [ -n "$3" ]; then curl -s -w '\nHTTP %{http_code}' -X POST $B/api/evidence -H "Authorization: Bearer $1" -F "metadata=$2;type=application/json" -F "file=@$3;type=${4:-text/plain}"
  else curl -s -w '\nHTTP %{http_code}' -X POST $B/api/evidence -H "Authorization: Bearer $1" -F "metadata=$2;type=application/json"; fi
}

# E3: registering evidence under a case number now requires that case to already exist (case-insensitive match),
# so every case number this checklist registers evidence under is created first. Tolerant of a case that already
# exists from a previous run (409 CASE_NUMBER_TAKEN, ignored) so the script stays runnable more than once.
ensure_case() { curl -s -o /dev/null -w '%{http_code}' -X POST $B/api/cases -H "Authorization: Bearer $TAD" -H 'Content-Type: application/json' -d "{\"caseNumber\":\"$1\",\"title\":\"Live check case $1\",\"leadOfficerId\":\"$COLLECTOR_ID\"}"; }
hdr "E3 prerequisite: create the cases this checklist registers evidence under"
for cn in FAB-LIVE-1 FAB-LIVE-2 FAB-LIVE-3 FAB-LIVE-4; do echo "   case $cn -> $(ensure_case $cn) (200/201 created, 409 already exists from an earlier run)"; done
echo "   register under an unknown case number -> $(status -X POST $B/api/evidence -H "Authorization: Bearer $TC" -F 'metadata={"caseId":"NO-SUCH-CASE-XYZ","type":"PHYSICAL","description":"x"};type=application/json')  $(python -c "import json;print(json.load(open('body.json'))['error'])")"

########################################################################################################
hdr "B1/B2/C1 register DIGITAL evidence (metadata carries a FORGED collector, must be ignored)"
UNIQ="LIVE-EVIDENCE-$(date +%s)-original-content"
printf '%s' "$UNIQ" > sample.txt
LOCAL_SHA=$(sha256sum sample.txt | cut -d' ' -f1)
META='{"caseId":"FAB-LIVE-1","type":"DIGITAL","description":"Suspect phone image","location":"Locker 4","collector":"forged@evil.example","collectorId":"00000000-0000-0000-0000-000000000000"}'
OUT=$(reg "$TC" "$META" sample.txt); echo "$OUT" | tail -1
echo "$OUT" | sed '$d' > r1.json
ID1=$(python -c "import json;print(json.load(open('r1.json'))['evidenceId'])")
cat r1.json | pick evidenceId status version createdBy currentCustodian fileCid fileSha256 fileSize metadataAvailable metadata.collectorId verification.status
say "local sha256 of the file : $LOCAL_SHA"
say "ledger fileSha256        : $(python -c "import json;print(json.load(open('r1.json'))['fileSha256'])")"
say "createdBy == collector id from JWT? $( [ "$(python -c "import json;print(json.load(open('r1.json'))['createdBy'])")" = "$COLLECTOR_ID" ] && echo YES || echo NO )"
FILE_CID=$(python -c "import json;print(json.load(open('r1.json'))['fileCid'])")
META_CID=$(python -c "import json;print(json.load(open('r1.json'))['metadataCid'])")
say "stored bytes fetched from IPFS by the file CID == original? $(curl -s -X POST "http://127.0.0.1:5001/api/v0/cat?arg=$FILE_CID" | sha256sum | cut -d' ' -f1 | grep -q $LOCAL_SHA && echo YES || echo NO)"

hdr "B1 register PHYSICAL evidence (no file)"
OUT=$(reg "$TA" '{"caseId":"FAB-LIVE-1","type":"PHYSICAL","description":"Kitchen knife","location":"Kitchen","notes":"bagged and sealed"}'); echo "$OUT" | tail -1
echo "$OUT" | sed '$d' | pick evidenceId evidenceType fileCid fileSha256 status createdByRole 2>/dev/null

########################################################################################################
hdr "B3 retrieve by id, by file CID, by metadata CID"
status $B/api/evidence/$ID1 -H "Authorization: Bearer $TAU"; echo " HTTP by id (AUDITOR)"; pick status version metadata.description verification.status < body.json
status $B/api/evidence/by-cid/$FILE_CID -H "Authorization: Bearer $TAU"; echo " HTTP by file CID"; python -c "import json;d=json.load(open('body.json'));print('   ids:',[e['evidenceId'] for e in d])"
status $B/api/evidence/by-cid/$META_CID -H "Authorization: Bearer $TAU"; echo " HTTP by metadata CID"
status $B/api/evidence/by-cid/bafkreigh2akiscaildcqabsyg3dfr6chu3fgpregiymsck7e7aqa4s52zy -H "Authorization: Bearer $TAU"; echo " HTTP by unknown CID"; cat body.json | pick error
status $B/api/evidence/EV-00000000-0000-0000-0000-000000000000 -H "Authorization: Bearer $TAU"; echo " HTTP unknown id"; cat body.json | pick error

hdr "C2 verify untouched evidence"
status $B/api/evidence/$ID1/verify -H "Authorization: Bearer $TAU"; echo " HTTP"; pick status ledgerVersion file.result file.expectedSha256 file.actualSha256 metadata.result < body.json

########################################################################################################
hdr "C2 DELIBERATE CORRUPTION: change one word inside the file's block on the IPFS node's disk, then restart the node"
BLK=$(docker exec be-ipfs sh -c "grep -rl '$UNIQ' /data/ipfs/blocks 2>/dev/null | head -1")
say "block file on the node: $BLK"
say "before: $(docker exec be-ipfs cat "$BLK")"
docker exec be-ipfs sh -c "sed -i 's/original/0RIGINAL/' '$BLK'"
say "after : $(docker exec be-ipfs cat "$BLK")"
docker restart be-ipfs >/dev/null; wait_ipfs
say "IPFS still answers a cat for the same CID (content-addressing did NOT catch it):"
say "   cat -> $(curl -s -X POST "http://127.0.0.1:5001/api/v0/cat?arg=$FILE_CID")"
status $B/api/evidence/$ID1/verify -H "Authorization: Bearer $TAU"; echo " HTTP verify"; pick status file.result file.expectedSha256 file.actualSha256 metadata.result < body.json
status "$B/api/evidence/$ID1?verify=true" -H "Authorization: Bearer $TAU"; echo " HTTP GET ?verify=true"; pick verification.status status < body.json

########################################################################################################
hdr "C2 NOT_FOUND: register another item, delete its file block from the node's disk, restart"
UNIQ2="LIVE-EVIDENCE-$(date +%s)-to-be-lost"; printf '%s' "$UNIQ2" > lost.txt
OUT=$(reg "$TC" '{"caseId":"FAB-LIVE-1","type":"DIGITAL","description":"Will go missing"}' lost.txt); echo "$OUT" | sed '$d' > r2.json
ID2=$(python -c "import json;print(json.load(open('r2.json'))['evidenceId'])")
BLK2=$(docker exec be-ipfs sh -c "grep -rl '$UNIQ2' /data/ipfs/blocks 2>/dev/null | head -1")
docker exec be-ipfs sh -c "test -f $BLK2 && rm -f $BLK2 && echo removed-block"; docker restart be-ipfs >/dev/null; wait_ipfs
T0=$(date +%s%N); status $B/api/evidence/$ID2/verify -H "Authorization: Bearer $TAU"; T1=$(date +%s%N); echo " HTTP verify ($(( (T1-T0)/1000000 )) ms)"; pick status file.result file.actualSha256 metadata.result < body.json

########################################################################################################
hdr "B4 versioned update (ANALYST), old version stays readable, stale update rejected"
ID3=$(reg "$TC" '{"caseId":"FAB-LIVE-1","type":"PHYSICAL","description":"Wallet","location":"Desk 2"}' | sed '$d' | python -c "import sys,json;print(json.load(sys.stdin)['evidenceId'])")
status -X PUT $B/api/evidence/$ID3 -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' -d '{"expectedVersion":1,"reason":"Location corrected after audit","location":"Locker 9"}'; echo " HTTP update v1->v2"; pick version lastAction lastReason metadata.location metadata.description metadata.metadataVersion metadata.previousMetadataCid < body.json
status $B/api/evidence/$ID3/versions/1 -H "Authorization: Bearer $TAU"; echo " HTTP GET version 1 (old)"; pick version metadata.location < body.json
status $B/api/evidence/$ID3/versions/2 -H "Authorization: Bearer $TAU"; echo " HTTP GET version 2"; pick version metadata.location < body.json
status -X PUT $B/api/evidence/$ID3 -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' -d '{"expectedVersion":1,"reason":"stale attempt","notes":"x"}'; echo " HTTP stale update (expectedVersion=1, record is at 2)"; pick error message < body.json
status -X PUT $B/api/evidence/$ID3 -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' -d '{"expectedVersion":2,"reason":" ","notes":"x"}'; echo " HTTP blank reason"; pick error < body.json
status -X PUT $B/api/evidence/$ID3 -H "Authorization: Bearer $TJ" -H 'Content-Type: application/json' -d '{"expectedVersion":2,"reason":"judge tries","notes":"x"}'; echo " HTTP JUDGE update"; pick error < body.json

hdr "C3 ledger history (tx ids and ledger timestamps)"
status $B/api/evidence/$ID3/history -H "Authorization: Bearer $TAU"; echo " HTTP"
python -c "
import json
for e in json.load(open('body.json')): print('   v%d  %-17s tx=%s..  at=%s  by=%s reason=%r' % (e['version'],e['action'],e['txId'][:16],e['timestamp'],e['actorRole'],e['reason']))"

########################################################################################################
hdr "B5 disposal: request (PROSECUTOR) -> COLLECTOR cannot approve -> stale approval rejected -> JUDGE approves"
ID4=$(reg "$TC" '{"caseId":"FAB-LIVE-1","type":"PHYSICAL","description":"Old ledger book"}' | sed '$d' | python -c "import sys,json;print(json.load(sys.stdin)['evidenceId'])")
status -X POST $B/api/evidence/$ID4/disposal -H "Authorization: Bearer $TP" -H 'Content-Type: application/json' -d '{"expectedVersion":1,"reason":"Case closed by order 42/2026"}'; echo " HTTP request disposal"; pick status version disposal.state disposal.reason lastAction < body.json
status -X POST $B/api/evidence/$ID4/disposal/approve -H "Authorization: Bearer $TC" -H 'Content-Type: application/json' -d '{"expectedVersion":2,"note":"self approve"}'; echo " HTTP COLLECTOR approve"; pick error < body.json
status -X POST $B/api/evidence/$ID4/disposal/approve -H "Authorization: Bearer $TJ" -H 'Content-Type: application/json' -d '{"expectedVersion":1,"note":"looked at old version"}'; echo " HTTP JUDGE approve with stale version"; pick error < body.json
status -X POST $B/api/evidence/$ID4/disposal/approve -H "Authorization: Bearer $TJ" -H 'Content-Type: application/json' -d '{"expectedVersion":2,"note":"Order verified"}'; echo " HTTP JUDGE approve"; pick status version disposal.state lastAction lastReason < body.json
status -X PUT $B/api/evidence/$ID4 -H "Authorization: Bearer $TC" -H 'Content-Type: application/json' -d '{"expectedVersion":3,"reason":"edit after disposal","notes":"x"}'; echo " HTTP update after DISPOSED"; pick error message < body.json
status $B/api/evidence/$ID4 -H "Authorization: Bearer $TAU"; echo " HTTP the DISPOSED record is still readable"; pick status version < body.json
status $B/api/evidence/$ID4/history -H "Authorization: Bearer $TAU"; python -c "
import json
print('   history entries still on ledger:', [ (e['version'],e['action']) for e in json.load(open('body.json'))])"

hdr "C-02: there is no delete"
for r in "collector:$TC" "admin:$TAD" "judge:$TJ"; do n=${r%%:*}; t=${r#*:}; echo "   DELETE /api/evidence/{id} as $n -> $(status -X DELETE $B/api/evidence/$ID4 -H "Authorization: Bearer $t")  $(python -c "import json;print(json.load(open('body.json'))['error'])")"; done

########################################################################################################
hdr "A3 roles: who may register (403 for the rest)"
for r in collector forensic-analyst prosecutor judge auditor admin; do t=$(tok $r); code=$(status -X POST $B/api/evidence -H "Authorization: Bearer $t" -F 'metadata={"caseId":"FAB-LIVE-3","type":"PHYSICAL","description":"role probe"};type=application/json'); echo "   $r -> $code"; done

########################################################################################################
hdr "B2 upload limits and types"
printf 'MZ' > tiny.exe
echo "   disallowed type (application/x-msdownload) -> $(status -X POST $B/api/evidence -H "Authorization: Bearer $TC" -F 'metadata={"caseId":"FAB-LIVE-2","type":"DIGITAL","description":"x"};type=application/json' -F 'file=@tiny.exe;type=application/x-msdownload')  $(python -c "import json;d=json.load(open('body.json'));print(d['error'],'|',d['message'])")"
: > empty.txt
echo "   empty file for DIGITAL -> $(status -X POST $B/api/evidence -H "Authorization: Bearer $TC" -F 'metadata={"caseId":"FAB-LIVE-2","type":"DIGITAL","description":"x"};type=application/json' -F 'file=@empty.txt;type=text/plain')  $(python -c "import json;print(json.load(open('body.json'))['error'])")"
echo "   file on PHYSICAL evidence -> $(status -X POST $B/api/evidence -H "Authorization: Bearer $TC" -F 'metadata={"caseId":"FAB-LIVE-2","type":"PHYSICAL","description":"x"};type=application/json' -F 'file=@sample.txt;type=text/plain')  $(python -c "import json;print(json.load(open('body.json'))['error'])")"
echo "   missing required metadata field -> $(status -X POST $B/api/evidence -H "Authorization: Bearer $TC" -F 'metadata={"type":"PHYSICAL"};type=application/json')  $(python -c "import json;print([f['field'] for f in json.load(open('body.json'))['fieldErrors']])")"
head -c 60000000 /dev/urandom > big.png
echo "   60 MB file (limit 50 MB) -> $(status -X POST $B/api/evidence -H "Authorization: Bearer $TC" -F 'metadata={"caseId":"FAB-LIVE-2","type":"DIGITAL","description":"too big"};type=application/json' -F 'file=@big.png;type=image/png')  $(python -c "import json;d=json.load(open('body.json'));print(d['error'])")"
head -c 20000000 /dev/urandom > mid.png; MID_SHA=$(sha256sum mid.png | cut -d' ' -f1)
T0=$(date +%s%N); OUT=$(reg "$TC" '{"caseId":"FAB-LIVE-2","type":"DIGITAL","description":"20 MB image"}' mid.png image/png); T1=$(date +%s%N)
echo "   20 MB file -> $(echo "$OUT" | tail -1) in $(( (T1-T0)/1000000 )) ms"
echo "$OUT" | sed '$d' > r5.json
say "local sha256 : $MID_SHA"; say "ledger sha256: $(python -c "import json;print(json.load(open('r5.json'))['fileSha256'])")   size=$(python -c "import json;print(json.load(open('r5.json'))['fileSize'])")"
ID5=$(python -c "import json;print(json.load(open('r5.json'))['evidenceId'])")
status $B/api/evidence/$ID5/verify -H "Authorization: Bearer $TAU"; echo " HTTP verify of the 20 MB file"; pick status file.result < body.json

########################################################################################################
hdr "F1 IPFS outage: ledger information survives, verify says 503 (not NOT_FOUND)"
docker stop be-ipfs >/dev/null
status $B/api/evidence/$ID3 -H "Authorization: Bearer $TAU"; echo " HTTP GET during outage"; pick status version metadataAvailable metadata < body.json
status $B/api/evidence/$ID3/verify -H "Authorization: Bearer $TAU"; echo " HTTP verify during outage"; pick error message < body.json
echo "   register during outage -> $(status -X POST $B/api/evidence -H "Authorization: Bearer $TC" -F 'metadata={"caseId":"FAB-LIVE-2","type":"PHYSICAL","description":"x"};type=application/json')  $(python -c "import json;print(json.load(open('body.json'))['error'])")"
docker start be-ipfs >/dev/null; wait_ipfs
hdr "G4 health with the reference ledger"
status $B/actuator/health -H "Authorization: Bearer $TAD"; echo " HTTP"; python -c "
import json;d=json.load(open('body.json'))
print('   overall:',d['status']);print('   ledger :',d['components']['ledger']);print('   ipfs   :',d['components']['ipfs'])"

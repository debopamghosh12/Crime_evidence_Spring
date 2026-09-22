#!/bin/bash
# Live verification of Phase 3 (D1-D3 status, custody transfer, timeline; E1/E2 cases) against a running backend.
# Needs WORKDIR (scratch dir OUTSIDE the repo containing env.sh), real Postgres, an OFFLINE Kubo (C-09), and either ledger.
export MSYS_NO_PATHCONV=1
SP="${WORKDIR:?set WORKDIR to a scratch directory OUTSIDE the repo that contains env.sh (secrets and Fabric paths)}"
. "$SP/env.sh"; cd "$SP"
B=http://localhost:8080; PW="$BLOCKEVIDENCE_DEV_SEED_PASSWORD"
tok() { curl -s -X POST $B/api/auth/login -H 'Content-Type: application/json' -d "{\"email\":\"$1@blockevidence.local\",\"password\":\"$PW\"}" | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])"; }
uid() { curl -s $B/api/auth/me -H "Authorization: Bearer $1" | python -c "import sys,json;print(json.load(sys.stdin)['id'])"; }
pick() { python -c "
import sys,json
d=json.load(sys.stdin)
def get(o,p):
    for k in p.split('.'):
        o=o.get(k) if isinstance(o,dict) else None
    return o
for p in sys.argv[1:]: print('   %-24s %s' % (p, get(d,p)))
" "$@"; }
code() { curl -s -o body.json -w '%{http_code}' "$@"; }
err() { python -c "import json;d=json.load(open('body.json'));print(d.get('error'),'|',d.get('message','')[:90])"; }
hdr() { echo; echo "### $*"; }
J='Content-Type: application/json'

TC=$(tok collector); TA=$(tok forensic-analyst); TP=$(tok prosecutor); TJ=$(tok judge); TAU=$(tok auditor); TAD=$(tok admin)
UC=$(uid $TC); UA=$(uid $TA); UP=$(uid $TP); UJ=$(uid $TJ); UAU=$(uid $TAU)
reg() { curl -s -X POST $B/api/evidence -H "Authorization: Bearer $TC" -F "metadata={\"caseId\":\"$1\",\"type\":\"PHYSICAL\",\"description\":\"$2\"};type=application/json" | python -c "import sys,json;print(json.load(sys.stdin)['evidenceId'])"; }
show() { pick status version currentCustodian lastAction lastReason < body.json; }

########################################################################################################
hdr "D1 status state machine (analyst moves COLLECTED -> PROCESSING -> ANALYZED)"
E1=$(reg FAB-P3-1 "Laptop")
echo "   evidence $E1"
echo "   -> $(code -X POST $B/api/evidence/$E1/status -H "Authorization: Bearer $TA" -H "$J" -d '{"expectedVersion":1,"status":"PROCESSING","reason":"sent to forensic lab"}') PROCESSING"; show
echo "   -> $(code -X POST $B/api/evidence/$E1/status -H "Authorization: Bearer $TA" -H "$J" -d '{"expectedVersion":2,"status":"ANALYZED","reason":"imaging complete"}') ANALYZED"; show
echo "   illegal jump ANALYZED -> RELEASED (skips ARCHIVED):   $(code -X POST $B/api/evidence/$E1/status -H "Authorization: Bearer $TA" -H "$J" -d '{"expectedVersion":3,"status":"RELEASED","reason":"skip"}')  $(err)"
echo "   backwards ANALYZED -> COLLECTED:                      $(code -X POST $B/api/evidence/$E1/status -H "Authorization: Bearer $TA" -H "$J" -d '{"expectedVersion":3,"status":"COLLECTED","reason":"undo"}')  $(err)"
echo "   straight to DISPOSED via status:                      $(code -X POST $B/api/evidence/$E1/status -H "Authorization: Bearer $TP" -H "$J" -d '{"expectedVersion":3,"status":"DISPOSED","reason":"shortcut"}')  $(err)"
echo "   stale version:                                        $(code -X POST $B/api/evidence/$E1/status -H "Authorization: Bearer $TA" -H "$J" -d '{"expectedVersion":1,"status":"ARCHIVED","reason":"r"}')  $(err)"
echo "   JUDGE tries to change status:                         $(code -X POST $B/api/evidence/$E1/status -H "Authorization: Bearer $TJ" -H "$J" -d '{"expectedVersion":3,"status":"ARCHIVED","reason":"r"}')  $(err)"
echo "   blank reason:                                         $(code -X POST $B/api/evidence/$E1/status -H "Authorization: Bearer $TA" -H "$J" -d '{"expectedVersion":3,"status":"ARCHIVED","reason":" "}')  $(err)"
echo "   legal step ANALYZED -> ARCHIVED (prosecutor):         $(code -X POST $B/api/evidence/$E1/status -H "Authorization: Bearer $TP" -H "$J" -d '{"expectedVersion":3,"status":"ARCHIVED","reason":"case file complete"}')"; show

########################################################################################################
hdr "D2 two-step custody transfer"
E2=$(reg FAB-P3-1 "Phone")
echo "   evidence $E2 (custodian = collector $UC)"
echo "   initiate collector -> analyst:                        $(code -X POST $B/api/evidence/$E2/transfers -H "Authorization: Bearer $TC" -H "$J" -d "{\"expectedVersion\":1,\"toUserId\":\"$UA\",\"reason\":\"forensic analysis\",\"notes\":\"sealed bag 7, seal intact\"}")"; show
echo "   -> custody has NOT moved yet (still the collector)"
echo "   pending list for the ANALYST:";  code $B/api/transfers/pending -H "Authorization: Bearer $TA" >/dev/null; python -c "
import json
for p in json.load(open('body.json')): print('     ',p['evidenceId'][:11]+'..','from',p['fromUser'][:8]+'..','reason=%r'%p['reason'],'notes=%r'%p['notes'],'version',p['version'])"
echo "   pending list for the COLLECTOR (sender):              $(code $B/api/transfers/pending -H "Authorization: Bearer $TC") -> $(python -c "import json;print(len(json.load(open('body.json'))),'items')")"
echo "   AUDITOR tries to initiate:                            $(code -X POST $B/api/evidence/$E2/transfers -H "Authorization: Bearer $TAU" -H "$J" -d "{\"expectedVersion\":2,\"toUserId\":\"$UA\",\"reason\":\"r\"}")  $(err)"
echo "   collector tries a 2nd transfer while one is pending:  $(code -X POST $B/api/evidence/$E2/transfers -H "Authorization: Bearer $TC" -H "$J" -d "{\"expectedVersion\":2,\"toUserId\":\"$UP\",\"reason\":\"r\"}")  $(err)"
echo "   prosecutor (not the receiver) tries to accept:        $(code -X POST $B/api/evidence/$E2/transfers/accept -H "Authorization: Bearer $TP" -H "$J" -d '{"expectedVersion":2,"note":"n"}')  $(err)"
echo "   collector (the sender) tries to accept:               $(code -X POST $B/api/evidence/$E2/transfers/accept -H "Authorization: Bearer $TC" -H "$J" -d '{"expectedVersion":2,"note":"n"}')  $(err)"
echo "   ANALYST accepts:                                      $(code -X POST $B/api/evidence/$E2/transfers/accept -H "Authorization: Bearer $TA" -H "$J" -d '{"expectedVersion":2,"note":"received intact, seal verified"}')"; show
echo "   old custodian (collector) tries to hand it on:        $(code -X POST $B/api/evidence/$E2/transfers -H "Authorization: Bearer $TC" -H "$J" -d "{\"expectedVersion\":3,\"toUserId\":\"$UP\",\"reason\":\"r\"}")  $(err)"
echo "   new custodian (analyst) -> prosecutor:                $(code -X POST $B/api/evidence/$E2/transfers -H "Authorization: Bearer $TA" -H "$J" -d "{\"expectedVersion\":3,\"toUserId\":\"$UP\",\"reason\":\"for court filing\"}")"
echo "   prosecutor REJECTS:                                   $(code -X POST $B/api/evidence/$E2/transfers/reject -H "Authorization: Bearer $TP" -H "$J" -d '{"expectedVersion":4,"note":"not ready to receive"}')"; show
echo "   analyst -> judge, then analyst CANCELS:               $(code -X POST $B/api/evidence/$E2/transfers -H "Authorization: Bearer $TA" -H "$J" -d "{\"expectedVersion\":5,\"toUserId\":\"$UJ\",\"reason\":\"court custody\"}") / $(code -X POST $B/api/evidence/$E2/transfers/cancel -H "Authorization: Bearer $TA" -H "$J" -d '{"expectedVersion":6,"note":"wrong court"}')"; show
echo "   receiver checks:"
echo "     to an AUDITOR (cannot hold evidence):               $(code -X POST $B/api/evidence/$E2/transfers -H "Authorization: Bearer $TA" -H "$J" -d "{\"expectedVersion\":7,\"toUserId\":\"$UAU\",\"reason\":\"r\"}")  $(err)"
echo "     to an unknown user id:                              $(code -X POST $B/api/evidence/$E2/transfers -H "Authorization: Bearer $TA" -H "$J" -d "{\"expectedVersion\":7,\"toUserId\":\"$(cat /proc/sys/kernel/random/uuid 2>/dev/null || python -c 'import uuid;print(uuid.uuid4())')\",\"reason\":\"r\"}")  $(err)"
echo "     to yourself:                                        $(code -X POST $B/api/evidence/$E2/transfers -H "Authorization: Bearer $TA" -H "$J" -d "{\"expectedVersion\":7,\"toUserId\":\"$UA\",\"reason\":\"r\"}")  $(err)"
echo "   forged sender field in the body is ignored (sender = token):"
code -X POST $B/api/evidence/$E2/transfers -H "Authorization: Bearer $TA" -H "$J" -d "{\"expectedVersion\":7,\"toUserId\":\"$UP\",\"reason\":\"forged-sender probe\",\"from\":\"$UC\",\"sender\":\"$UC\",\"actorId\":\"$UC\"}" >/dev/null
python -c "import json;print('     HTTP ok; custodian still',json.load(open('body.json'))['currentCustodian'][:8]+'.. (analyst), lastAction',json.load(open('body.json'))['lastAction'])"
code -X POST $B/api/evidence/$E2/transfers/cancel -H "Authorization: Bearer $TA" -H "$J" -d '{"expectedVersion":8,"note":"probe done"}' >/dev/null

########################################################################################################
hdr "D3 custody timeline (built from the ledger history; each step has its own ledger txId)"
echo "   users: collector=${UC:0:8}.. analyst=${UA:0:8}.. prosecutor=${UP:0:8}.. judge=${UJ:0:8}.."
code $B/api/evidence/$E2/chain-of-custody -H "Authorization: Bearer $TAU"; echo "   HTTP $(code $B/api/evidence/$E2/chain-of-custody -H "Authorization: Bearer $TAU") (AUDITOR may read)"
python - <<'PYEOF'
import json
t=json.load(open('body.json'))
short=lambda x: (x[:8]+'..') if x else '-'
print('   currentCustodian:', short(t['currentCustodian']), '| pendingTransfer:', t['pendingTransfer'])
for e in t['events']:
    print('   v%-2d %-19s from=%-10s to=%-10s after=%-10s tx=%s.. reason=%r note=%r' % (e['version'],e['type'],short(e['fromUser']),short(e['toUser']),short(e['custodianAfter']),e['txId'][:12],e['reason'],e['resolutionNote']))
PYEOF
echo "   timeline of an item with NO transfers (E1):"; code $B/api/evidence/$E1/chain-of-custody -H "Authorization: Bearer $TAU" >/dev/null; python -c "
import json;t=json.load(open('body.json'));print('     events:',[e['type'] for e in t['events']],'(status changes are not custody events)')"
echo "   unknown id: $(code $B/api/evidence/EV-00000000-0000-4000-8000-000000000000/chain-of-custody -H "Authorization: Bearer $TAU") $(err)"
if [ -f "$SP/persist_id.txt" ]; then OLD=$(cat "$SP/persist_id.txt"); echo "   an item written BEFORE transfers existed (chaincode v1.1 era) still reads:  GET $(code $B/api/evidence/$OLD -H "Authorization: Bearer $TAU"), timeline $(code $B/api/evidence/$OLD/chain-of-custody -H "Authorization: Bearer $TAU")"; fi
echo "   no DELETE on these routes:  $(code -X DELETE $B/api/evidence/$E2/transfers -H "Authorization: Bearer $TAD") $(code -X DELETE $B/api/evidence/$E2/status -H "Authorization: Bearer $TAD")"

########################################################################################################
hdr "E1 create and manage cases (PostgreSQL, Flyway V2)"
CN="FAB-P3-CASE-$RANDOM"
echo "   ADMIN creates $CN with the collector as lead officer:  $(code -X POST $B/api/cases -H "Authorization: Bearer $TAD" -H "$J" -d "{\"caseNumber\":\"$CN\",\"title\":\"Burglary at 12 High Street\",\"description\":\"Break-in, laptop and phone seized\",\"leadOfficerId\":\"$UC\"}")"
CASE=$(python -c "import json;print(json.load(open('body.json'))['id'])"); pick caseNumber status leadOfficerId < body.json
python -c "import json;d=json.load(open('body.json'));print('   members:',[(m['userId'][:8]+'..',m['caseRole']) for m in d['members']],' createdBy:',d['createdBy'][:8]+'.. (admin, from the token)')"
echo "   same number again (different case):                    $(code -X POST $B/api/cases -H "Authorization: Bearer $TAD" -H "$J" -d "{\"caseNumber\":\"$(echo $CN | tr 'A-Z' 'a-z')\",\"title\":\"dup\",\"leadOfficerId\":\"$UC\"}")  $(err)"
echo "   lead officer is an AUDITOR:                            $(code -X POST $B/api/cases -H "Authorization: Bearer $TAD" -H "$J" -d "{\"caseNumber\":\"$CN-X\",\"title\":\"t\",\"leadOfficerId\":\"$UAU\"}")  $(err)"
echo "   COLLECTOR tries to create a case:                      $(code -X POST $B/api/cases -H "Authorization: Bearer $TC" -H "$J" -d "{\"caseNumber\":\"$CN-Y\",\"title\":\"t\",\"leadOfficerId\":\"$UC\"}")  $(err)"
echo "   bad case number:                                       $(code -X POST $B/api/cases -H "Authorization: Bearer $TAD" -H "$J" -d '{"caseNumber":"bad number!","title":"t"}')  fields: $(python -c "import json;print([f['field'] for f in json.load(open('body.json'))['fieldErrors']])")"
echo "   any role can read: AUDITOR list $(code $B/api/cases -H "Authorization: Bearer $TAU"), get $(code $B/api/cases/$CASE -H "Authorization: Bearer $TAU"); anonymous $(code $B/api/cases)"

hdr "E2 assign officers to the case, with a role on the case"
echo "   add analyst as FORENSIC_ANALYST:                       $(code -X POST $B/api/cases/$CASE/members -H "Authorization: Bearer $TP" -H "$J" -d "{\"userId\":\"$UA\",\"caseRole\":\"FORENSIC_ANALYST\"}")"
echo "   add the same user again:                               $(code -X POST $B/api/cases/$CASE/members -H "Authorization: Bearer $TP" -H "$J" -d "{\"userId\":\"$UA\",\"caseRole\":\"OBSERVER\"}")  $(err)"
echo "   add someone as LEAD_OFFICER through members:           $(code -X POST $B/api/cases/$CASE/members -H "Authorization: Bearer $TP" -H "$J" -d "{\"userId\":\"$UJ\",\"caseRole\":\"LEAD_OFFICER\"}")  $(err)"
echo "   add prosecutor as PROSECUTOR, judge as OBSERVER:       $(code -X POST $B/api/cases/$CASE/members -H "Authorization: Bearer $TP" -H "$J" -d "{\"userId\":\"$UP\",\"caseRole\":\"PROSECUTOR\"}") / $(code -X POST $B/api/cases/$CASE/members -H "Authorization: Bearer $TP" -H "$J" -d "{\"userId\":\"$UJ\",\"caseRole\":\"OBSERVER\"}")"
python -c "import json;print('   team now:',[(m['userId'][:8]+'..',m['caseRole']) for m in json.load(open('body.json'))['members']])"
echo "   COLLECTOR tries to add a member:                       $(code -X POST $B/api/cases/$CASE/members -H "Authorization: Bearer $TC" -H "$J" -d "{\"userId\":\"$UAU\",\"caseRole\":\"OBSERVER\"}")  $(err)"
echo "   remove the judge from the team:                        $(code -X DELETE $B/api/cases/$CASE/members/$UJ -H "Authorization: Bearer $TP")"
echo "   remove the LEAD officer (must be refused):             $(code -X DELETE $B/api/cases/$CASE/members/$UC -H "Authorization: Bearer $TP")  $(err)"
echo "   remove someone not on the team:                        $(code -X DELETE $B/api/cases/$CASE/members/$UJ -H "Authorization: Bearer $TP")  $(err)"
echo "   change the lead officer to the prosecutor + rename:    $(code -X PUT $B/api/cases/$CASE -H "Authorization: Bearer $TAD" -H "$J" -d "{\"title\":\"Burglary at 12 High St (renamed)\",\"leadOfficerId\":\"$UP\"}")"
python -c "import json;d=json.load(open('body.json'));print('   title:',d['title'],'| lead:',d['leadOfficerId'][:8]+'..','| team:',sorted((m['userId'][:8]+'..',m['caseRole']) for m in d['members']))"
echo "   update with nothing to change:                         $(code -X PUT $B/api/cases/$CASE -H "Authorization: Bearer $TAD" -H "$J" -d '{}')  fields: $(python -c "import json;print([f['field'] for f in json.load(open('body.json'))['fieldErrors']])")"
echo "   DELETE a case (no such route):                         $(code -X DELETE $B/api/cases/$CASE -H "Authorization: Bearer $TAD")"
echo "   the case number is a valid ledger caseId (NOT enforced yet, E3): $(curl -s -o body.json -w '%{http_code}' -X POST $B/api/evidence -H "Authorization: Bearer $TC" -F "metadata={\"caseId\":\"$CN\",\"type\":\"PHYSICAL\",\"description\":\"linked by number\"};type=application/json")"

hdr "Database (what is actually stored)"
docker exec be-postgres psql -U blockevidence -d blockevidence -c "select case_number, status, title from cases order by created_at desc limit 2" 2>&1 | sed 's/^/   /'
docker exec be-postgres psql -U blockevidence -d blockevidence -c "select case_role, count(*) from case_members group by case_role order by 1" 2>&1 | sed 's/^/   /'
docker exec be-postgres psql -U blockevidence -d blockevidence -c "select version, description, success from flyway_schema_history order by installed_rank" 2>&1 | sed 's/^/   /'

#!/usr/bin/env bash
# End-to-end scenarios of the in-memory orchestrator (docs/orchestrator.md), with assertions.
# Flow "alta-cliente": database insert -> parallel lookups (RestClient 1.0.0 and 2.0.0 side by side)
# -> CRM creation (with compensation) -> scoring (abort rule) -> notification (fire-and-forget).
# Requires deploy.sh to have run. Exits non-zero on the first failed expectation.

source "$(dirname "$0")/env.sh"

API="$HTTP_URL/atlas/api"
PLUGIN_REST_V1="$ATLAS_HOME/atlas-plugin-restclient/target/atlas-plugin-restclient-$VERSION.jar"
failures=0
suffix="$(date +%s | tail -c 7)"

ok()   { printf '  \033[32mOK\033[0m   %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; failures=$((failures + 1)); }

# check <description> <python expression over: r (flow result), db (dni list), crm (dni list),
#        deletes (crm ids), notified (dni list)>
check() {
    local description="$1" expression="$2" state
    state="$(curl -s "$API/flows/data/clientes")|$(curl -s "$API/mock/estado")"
    if RESULT="$result" STATE="$state" python3 -I -c '
import json, os, sys
r = json.loads(os.environ["RESULT"] or "{}")
db_raw, mock_raw = os.environ["STATE"].split("|", 1)
db = [row["dni"] for row in json.loads(db_raw)]
mock = json.loads(mock_raw)
crm = [c["dni"] for c in mock["crm"]]
deletes = mock["crmDeletes"]
notified = [n["dni"] for n in mock["notifications"]]
sys.exit(0 if eval(sys.argv[1]) else 1)' "$expression"; then
        ok "$description"
    else
        fail "$description   [$expression]"
        printf '       result: %s\n' "$result"
    fi
}

run_flow() {
    result="$(curl -s -X POST -H 'Content-Type: application/json' -d "$2" "$API/flows/alta-cliente$1")"
}

curl -s -X POST "$API/mock/reset" > /dev/null

log "A. Happy path"
dni="1${suffix}1"
run_flow "" "{\"dni\":\"$dni\",\"nombre\":\"Ana\"}"
check "flow COMPLETED" "r['status'] == 'COMPLETED'"
check "row committed in the database" "'$dni' in db"
check "customer created in the CRM" "'$dni' in crm"
check "parallel lookups served by RestClient 1.0.0 (OkHttp 4.12.0) and 2.0.0 (OkHttp 5.3.0)" \
    "r['outputs']['buro']['client'] == {'pluginVersion': '1.0.0', 'okhttpVersion': '4.12.0'} and r['outputs']['listas']['client'] == {'pluginVersion': '2.0.0', 'okhttpVersion': '5.3.0'}"
sleep 1
check "fire-and-forget notification delivered after the commit" "'$dni' in notified and r['dispatched'] == ['notificar']"

log "B. Business rule aborts the flow (scoring RECHAZADO)"
dni="2${suffix}9"
run_flow "" "{\"dni\":\"$dni\",\"nombre\":\"Bruno\"}"
check "flow ABORTED at scoring" "r['status'] == 'ABORTED' and r['failedStep'] == 'scoring' and r['message'] == 'Scoring rechazado'"
check "database insert rolled back (JTA)" "'$dni' not in db"
check "CRM creation compensated (DELETE)" "r['compensated'] == ['crm'] and '$dni' not in crm and r['outputs']['crm']['body']['id'] in deletes"
sleep 1
check "notification discarded" "'$dni' not in notified"

log "C. Parallel step times out (credit bureau takes 5 s, limit 3 s)"
dni="3${suffix}3"
run_flow "" "{\"dni\":\"$dni\",\"nombre\":\"Carla\",\"delayMs\":5000}"
check "flow FAILED at buro after about 3 s" "r['status'] == 'FAILED' and r['failedStep'] == 'buro' and 'timed out' in r['message'] and 2900 <= r['durationMillis'] < 4500"
check "database insert rolled back, CRM never called" "'$dni' not in db and '$dni' not in crm"

log "D. Rule on a parallel step (customer in blacklist)"
dni="4${suffix}4"
run_flow "" "{\"dni\":\"$dni\",\"nombre\":\"Diego\"}"
check "flow ABORTED at consultas" "r['status'] == 'ABORTED' and r['failedStep'] == 'consultas' and r['message'] == 'Cliente en lista negra'"
check "database insert rolled back" "'$dni' not in db"

log "E. Flow completes, then the calling EJB fails: the transaction rolls back"
dni="5${suffix}5"
run_flow "?callerFails=true" "{\"dni\":\"$dni\",\"nombre\":\"Elena\"}"
sleep 1
check "caller failure reported" "r.get('callerFailed') is True"
check "database insert rolled back" "'$dni' not in db"
check "completed flow compensated after the rollback" "'$dni' not in crm and len(deletes) == 2"
check "notification discarded" "'$dni' not in notified"

log "F. Plugin catalog: two versions of rest-client side by side"
result="$(curl -s "$API/flows/plugins" | python3 -I -c 'import json,sys; print(json.dumps({"plugins": json.load(sys.stdin)}))')"
check "rest-client 1.0.0 and 2.0.0, database 1.0.0, orchestrator 1.0.0" \
    "sorted((p['name'], p['version']) for p in r['plugins']) == [('database','1.0.0'),('orchestrator','1.0.0'),('rest-client','1.0.0'),('rest-client','2.0.0')]"

log "G. Exact versions: remove rest-client 1.0.0 while the application runs"
asadmin undeploy atlas-plugin-restclient-1.0.0 > /dev/null
dni="6${suffix}6"
run_flow "" "{\"dni\":\"$dni\",\"nombre\":\"Gael\"}"
check "flow FAILED: no silent fallback to 2.0.0" "r['status'] == 'FAILED' and 'rest-client@1.0.0 is not installed' in r['message'] and '$dni' not in db"
asadmin deploy --force=true --type=osgi --name=atlas-plugin-restclient-1.0.0 "$PLUGIN_REST_V1" > /dev/null
dni="7${suffix}7"
run_flow "" "{\"dni\":\"$dni\",\"nombre\":\"Gina\"}"
check "1.0.0 reinstalled: flow COMPLETED without redeploying the application" "r['status'] == 'COMPLETED' and '$dni' in db"

log "Result"
if (( failures > 0 )); then
    echo "  $failures expectation(s) failed"
    exit 1
fi
echo "  All expectations met"

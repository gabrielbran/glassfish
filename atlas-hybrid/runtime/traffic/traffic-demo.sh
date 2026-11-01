#!/usr/bin/env bash
# End-to-end scenarios of the traffic lifecycle (ADR-011) on the cluster behind HAProxy, with assertions.
# Requires setup-cluster-demo.sh. Exits non-zero if any expectation fails.

source "$(dirname "$0")/../env.sh"

S="$ATLAS_HOME/target/traffic-run"
mkdir -p "$S"
LB=127.0.0.1:8000
API=traffic-demo/api
failures=0

ok()   { printf '  \033[32mOK\033[0m   %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; failures=$((failures + 1)); }
expect() { if eval "$2"; then ok "$1"; else fail "$1   [$2]"; fi; }

lb_state() { curl -s "127.0.0.1:8404/stats;csv" | awk -F, -v s="$1" '$1=="glassfish" && $2==s {print $18}'; }
lb_retries() { curl -s "127.0.0.1:8404/stats;csv" | awk -F, '$1=="glassfish" && $2=="BACKEND" {print $16 + $17}'; }
state_of() { asadmin atlas-traffic-status "$1" | grep -o "state=[A-Z]*" | tail -1 | cut -d= -f2; }
status_of() { asadmin atlas-traffic-status "$1" | grep "$1: state" | tail -1; }
wait_state() { for _ in $(seq 1 "${3:-30}"); do [[ "$(state_of "$1")" == "$2" ]] && return 0; sleep 1; done; return 1; }
wait_lb() { for _ in $(seq 1 "${3:-10}"); do [[ "$(lb_state "$1")" == "$2" ]] && return 0; sleep 1; done; return 1; }
instances_via_lb() { for _ in 1 2 3 4 5 6; do curl -s "$LB/$API/whoami"; done | grep -o '"i[0-9]"' | sort -u | tr -d '"' | tr '\n' ' '; }

log "1. Both instances validated and in the load balancer"
expect "i1 and i2 READY" '[[ "$(state_of i1)" == READY && "$(state_of i2)" == READY ]]'
expect "HAProxy: i1 and i2 UP" '[[ "$(lb_state i1)" == UP && "$(lb_state i2)" == UP ]]'
expect "traffic reaches both" '[[ "$(instances_via_lb)" == "i1 i2 " ]]'

log "2. Drain i1 with an EJB transaction + JDBC connection in flight (6 s)"
( curl -s -m 60 -X POST -o "$S/inflight.json" -w '%{http_code}' "localhost:28080/$API/orders?ms=6000" > "$S/inflight.code" ) &
sleep 1
expect "work in flight counted on i1 (http=1, jta=1, jdbc=1)" '[[ "$(status_of i1)" == *"http=1, jta=1, jdbc=1"* ]]'
( sleep 3.5; curl -s -o /dev/null -w '%{http_code}' "localhost:28080/$API/whoami" > "$S/after-grace.code"; lb_state i1 > "$S/lb-during.txt"; instances_via_lb > "$S/via-lb-during.txt" ) &
drain_status=0; asadmin atlas-drain --grace 2 i1 > "$S/drain.txt" || drain_status=$?
wait
expect "drain completed: DRAINED, exit code 0" '(( drain_status == 0 )) && grep -q "i1: DRAINED" "$S/drain.txt"'
expect "the in-flight transaction completed (HTTP 200, not cut)" '[[ "$(cat "$S/inflight.code")" == 200 ]]'
expect "after the grace period new requests to i1 get 503" '[[ "$(cat "$S/after-grace.code")" == 503 ]]'
expect "HAProxy took i1 out while draining" '[[ "$(cat "$S/lb-during.txt")" == DOWN ]]'
expect "meanwhile all traffic went to i2" '[[ "$(cat "$S/via-lb-during.txt")" == "i2 " ]]'
asadmin atlas-undrain i1 > /dev/null
expect "undrain: back to READY only after validating" 'wait_state i1 READY 15'
expect "HAProxy: i1 UP again" 'wait_lb i1 UP 10'

log "3. Drain timeout: a 10 s operation and a 3 s limit"
( curl -s -m 60 -X POST -o /dev/null -w '%{http_code}' "localhost:28080/$API/orders?ms=10000" > "$S/long.code" ) &
sleep 1
timeout_status=0; asadmin atlas-drain --grace 1 --timeout 3 i1 > "$S/timeout.txt" || timeout_status=$?
expect "atlas-drain fails (exit code != 0) and reports what is still running" '(( timeout_status != 0 )) && grep -q "i1: DRAINING.*http=1" "$S/timeout.txt"'
wait
expect "the long operation was not cut (HTTP 200)" '[[ "$(cat "$S/long.code")" == 200 ]]'
asadmin atlas-undrain i1 > /dev/null; wait_state i1 READY 15; wait_lb i1 UP 10

log "4. Validation gate: database down while i2 validates"
asadmin atlas-drain --grace 1 i2 > /dev/null
asadmin stop-database > /dev/null
asadmin atlas-undrain i2 > /dev/null
sleep 6
expect "i2 stays VALIDATING with the reason" '[[ "$(status_of i2)" == *"state=VALIDATING"*"lastFailure=jdbc/__default"* ]]'
expect "HAProxy keeps i2 out; traffic only on i1" '[[ "$(lb_state i2)" == DOWN && "$(instances_via_lb)" == "i1 " ]]'
asadmin start-database > /dev/null
expect "database back: i2 validates and becomes READY" 'wait_state i2 READY 30'
expect "HAProxy: i2 UP again" 'wait_lb i2 UP 10'

log "5. The application's own readiness check (MicroProfile Health)"
curl -s -X POST "localhost:29080/$API/readiness/down" > /dev/null
expect "application check DOWN on i2: HAProxy takes it out" 'wait_lb i2 DOWN 6'
curl -s -X POST "localhost:29080/$API/readiness/up" > /dev/null
expect "application check UP: back in" 'wait_lb i2 UP 6'

log "6. Rolling restart of the whole cluster under load (8 clients, transactional POSTs of 4 s)"
retries_before="$(lb_retries)"
rm -f "$S/stop"
python3 -I "$(dirname "$0")/load.py" "$LB" 8 "$S/stop" "$S/rolling.txt" 4000 > "$S/rolling.json" &
sleep 5
rolling_status=0; "$(dirname "$0")/rolling-restart.sh" atlas-c1 > "$S/rolling-restart.log" || rolling_status=$?
sleep 3
touch "$S/stop"; wait
summary="$(cat "$S/rolling.json")"
echo "  load: $summary"
expect "rolling restart completed" '(( rolling_status == 0 ))'
expect "zero failed requests" '[[ "$summary" == *"\"failed\": 0,"* ]]'
expect "zero requests replayed by HAProxy" '[[ "$(lb_retries)" == "$retries_before" ]]'
expect "both instances served traffic and are READY again" '[[ "$summary" == *"\"i1\""* && "$summary" == *"\"i2\""* && "$(state_of i1)" == READY && "$(state_of i2)" == READY ]]'

log "Result"
if (( failures > 0 )); then
    echo "  $failures expectation(s) failed"
    exit 1
fi
echo "  All expectations met"

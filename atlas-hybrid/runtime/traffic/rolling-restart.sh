#!/usr/bin/env bash
# Restarts (or patches) every instance of a cluster, one at a time, without dropping traffic:
#   drain -> stop -> [hook] -> start -> wait until validated (READY) -> next instance
# The load balancer always has validated instances, and no in-flight HTTP request, JTA transaction or
# JDBC connection is cut. Stops at the first instance that does not drain or does not become READY.
#
#   runtime/traffic/rolling-restart.sh <cluster> [grace-s=5] [drain-timeout-s=120] [ready-timeout-s=300]
#   ROLLING_HOOK="cmd" runs between stop and start (patch, redeploy...), with $INSTANCE set.

source "$(dirname "$0")/../env.sh"

CLUSTER="${1:?cluster name}"
GRACE="${2:-5}"
DRAIN_TIMEOUT="${3:-120}"
READY_TIMEOUT="${4:-300}"

state_of() {
    asadmin atlas-traffic-status "$1" | grep -o "state=[A-Z]*" | tail -1 | cut -d= -f2
}

for INSTANCE in $(asadmin list-instances --nostatus "$CLUSTER" | grep -v "executed successfully"); do
    log "$INSTANCE: drain"
    if ! asadmin atlas-drain --grace "$GRACE" --timeout "$DRAIN_TIMEOUT" "$INSTANCE" | grep "$INSTANCE:"; then
        echo "  $INSTANCE did not drain in ${DRAIN_TIMEOUT}s: stopping the rolling restart (instance left DRAINING)"
        exit 1
    fi
    log "$INSTANCE: stop"
    asadmin stop-instance "$INSTANCE" > /dev/null
    if [[ -n "${ROLLING_HOOK:-}" ]]; then
        log "$INSTANCE: hook"
        INSTANCE="$INSTANCE" bash -c "$ROLLING_HOOK"
    fi
    log "$INSTANCE: start and validate"
    asadmin start-instance "$INSTANCE" > /dev/null
    deadline=$((SECONDS + READY_TIMEOUT))
    until [[ "$(state_of "$INSTANCE")" == READY ]]; do
        if (( SECONDS > deadline )); then
            echo "  $INSTANCE not READY after ${READY_TIMEOUT}s: $(asadmin atlas-traffic-status "$INSTANCE" | grep "$INSTANCE:" | tail -1)"
            exit 1
        fi
        sleep 1
    done
    echo "  $INSTANCE READY"
    # Let the load balancer see it UP (rise 2 x inter 1s) before taking the next one out
    sleep "${ROLLING_SETTLE:-3}"
done
log "Rolling restart of $CLUSTER completed"

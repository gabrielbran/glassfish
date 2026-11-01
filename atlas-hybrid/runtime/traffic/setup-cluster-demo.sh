#!/usr/bin/env bash
# Demo environment for the traffic lifecycle (ADR-011): a two-instance cluster behind HAProxy.
# Idempotent. Requires "mvn install" and a running domain; HAProxy must be installed.
#
#   cluster atlas-c1: i1 (HTTP 28080, health 28090), i2 (HTTP 29080, health 29090)
#   HAProxy: 127.0.0.1:8000 -> i1/i2, stats on 127.0.0.1:8404/stats

source "$(dirname "$0")/../env.sh"

CLUSTER=atlas-c1
RUN_DIR="${RUN_DIR:-$ATLAS_HOME/target/traffic-run}"
mkdir -p "$RUN_DIR"

log "Database (Derby, demo) and pool connection validation"
asadmin start-database > /dev/null 2>&1 || true
# Without real validation the pool keeps dead connections after a database outage (findings H-13).
# "meta-data" is not enough (the Derby client answers it without a round trip): use the server-side
# validator GlassFish ships for each database; for Oracle: org.glassfish.api.jdbc.validation.OracleConnectionValidation
P=resources.jdbc-connection-pool.DerbyPool
asadmin set "$P.validation-classname=org.glassfish.api.jdbc.validation.DerbyConnectionValidation" > /dev/null
asadmin set "$P.connection-validation-method=custom-validation" > /dev/null
asadmin set "$P.is-connection-validation-required=true" > /dev/null
asadmin set "$P.fail-all-connections=true" > /dev/null

log "Cluster $CLUSTER"
if ! asadmin list-clusters | grep -q "^$CLUSTER "; then
    asadmin create-cluster "$CLUSTER"
    asadmin create-local-instance --cluster "$CLUSTER" --portbase 28000 i1
    asadmin create-local-instance --cluster "$CLUSTER" --portbase 29000 i2
fi
asadmin create-system-properties --target i1 HEALTH_LISTENER_PORT=28090 > /dev/null
asadmin create-system-properties --target i2 HEALTH_LISTENER_PORT=29090 > /dev/null

log "Traffic lifecycle on $CLUSTER"
TARGET="$CLUSTER" "$(dirname "$0")/configure-traffic.sh" | grep -E "executed|failed|already" || true

log "Demo application"
WAR="$ATLAS_HOME/atlas-traffic-demo-war/target/atlas-traffic-demo.war"
if asadmin list-applications "$CLUSTER" | grep -q atlas-traffic-demo; then
    asadmin deploy --force=true --target domain --contextroot=traffic-demo "$WAR" > /dev/null
else
    asadmin deploy --force=true --target "$CLUSTER" --contextroot=traffic-demo "$WAR" > /dev/null
fi

log "Restart $CLUSTER"
asadmin stop-cluster "$CLUSTER" > /dev/null 2>&1 || true
asadmin start-cluster "$CLUSTER"

log "HAProxy"
export ATLAS_HAPROXY_SOCKET="$RUN_DIR/haproxy.sock" ATLAS_HAPROXY_BIND=127.0.0.1:8000 ATLAS_HAPROXY_STATS=127.0.0.1:8404
[[ -f "$RUN_DIR/haproxy.pid" ]] && kill "$(cat "$RUN_DIR/haproxy.pid")" 2>/dev/null || true
sleep 1
haproxy -D -f "$(dirname "$0")/haproxy.cfg" -p "$RUN_DIR/haproxy.pid"
for port in 28090 29090; do
    for _ in $(seq 1 60); do [[ "$(curl -s -o /dev/null -w '%{http_code}' "localhost:$port/health/ready")" == 200 ]] && break; sleep 1; done
done
sleep 3
curl -s "127.0.0.1:8404/stats;csv" | awk -F, '$1=="glassfish"{print "  " $2 " " $18}'

#!/usr/bin/env bash
# Installs and configures the Atlas traffic lifecycle (ADR-011) for a target: "server" (the DAS) or a
# cluster. Idempotent. Restart the target afterwards (start-domain / restart-cluster).
#
#   TARGET=server  runtime/traffic/configure-traffic.sh        # DAS only (default)
#   TARGET=atlas-c1 runtime/traffic/configure-traffic.sh       # a cluster
#
# Validation (read-only) defaults to the demo Derby resource; for Oracle use for example
#   VALIDATION_RESOURCE=jdbc/clientes VALIDATION_SQL="SELECT 1 FROM DUAL"

source "$(dirname "$0")/../env.sh"

TARGET="${TARGET:-server}"
CONFIG_NAME="$([[ "$TARGET" == server ]] && echo server-config || echo "$TARGET-config")"
VALIDATION_RESOURCE="${VALIDATION_RESOURCE:-jdbc/__default}"
VALIDATION_SQL="${VALIDATION_SQL:-VALUES 1}"
MODULE="$ATLAS_HOME/atlas-server-traffic/target/atlas-server-traffic-$VERSION.jar"
DEFAULT_WEB="$DOMAIN_DIR/config/default-web.xml"
MARKER="atlas-drain-filter"

log "1. Server module (HK2): $GLASSFISH_HOME/glassfish/modules/atlas-server-traffic.jar"
# An add-on module next to the GlassFish ones; nothing of GlassFish is modified. Every node needs it.
cp "$MODULE" "$GLASSFISH_HOME/glassfish/modules/atlas-server-traffic.jar"

log "2. Drain filter for every web application ($DEFAULT_WEB)"
if ! grep -q "$MARKER" "$DEFAULT_WEB"; then
    python3 -I - "$DEFAULT_WEB" "$MARKER" <<'PY'
import sys
path, marker = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()
filter_decl = f"""
  <!-- {marker}: Atlas traffic lifecycle (ADR-011) -->
  <filter>
    <filter-name>{marker}</filter-name>
    <filter-class>org.atlas.traffic.web.DrainFilter</filter-class>
    <async-supported>true</async-supported>
  </filter>
  <filter-mapping>
    <filter-name>{marker}</filter-name>
    <url-pattern>/*</url-pattern>
    <dispatcher>REQUEST</dispatcher>
  </filter-mapping>
"""
# Filters declared in default-web.xml run before the application's own filters
first = min(i for i in (text.find("<filter>"), text.find("<servlet>")) if i >= 0)
open(path, "w", encoding="utf-8").write(text[:first] + filter_decl.lstrip("\n") + "\n  " + text[first:])
PY
    echo "  filter added (applies to applications deployed or restarted afterwards)"
else
    echo "  already present"
fi

log "3. Transaction monitoring on $CONFIG_NAME (needed to count active JTA transactions)"
asadmin set "configs.config.$CONFIG_NAME.monitoring-service.module-monitoring-levels.transaction-service=LOW"

log "4. Read-only validation on $TARGET: $VALIDATION_RESOURCE -> $VALIDATION_SQL"
asadmin create-system-properties --target "$TARGET" \
    "atlas.traffic.validation.1.resource=${VALIDATION_RESOURCE//:/\\:}:atlas.traffic.validation.1.sql=${VALIDATION_SQL//:/\\:}"

if [[ "$TARGET" != server ]]; then
    log "5. JDBC resource used by the validation, referenced from $TARGET"
    asadmin create-resource-ref --target "$TARGET" "$VALIDATION_RESOURCE" 2>&1 | grep -v "already exists" || true

    # Health probes must never queue behind business requests: with a saturated http-thread-pool they
    # time out and the load balancer ejects the last healthy instance (findings H-11). A dedicated
    # listener with its own small pool serves /health; port = ${HEALTH_LISTENER_PORT} of each instance.
    log "6. Dedicated health listener on $TARGET (own thread pool, port \${HEALTH_LISTENER_PORT})"
    if ! asadmin list-network-listeners "$TARGET" | grep -qx health-listener; then
        asadmin create-threadpool --target "$TARGET" --minthreadpoolsize 1 --maxthreadpoolsize 2 health-thread-pool
        asadmin create-protocol --target "$TARGET" health-protocol
        asadmin create-http --target "$TARGET" --default-virtual-server server health-protocol
        asadmin create-network-listener --target "$TARGET" --protocol health-protocol --threadpool health-thread-pool \
            --listenerport '${HEALTH_LISTENER_PORT}' health-listener
    else
        echo "  already present"
    fi
    echo "  Define HEALTH_LISTENER_PORT on every instance, e.g.: asadmin create-system-properties --target i1 HEALTH_LISTENER_PORT=28090 (portbase + 90: +81 is HTTPS)"
fi

echo
echo "Done. Restart $TARGET to load the module and the transaction monitoring level."

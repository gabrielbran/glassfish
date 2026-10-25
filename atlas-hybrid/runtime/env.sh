#!/usr/bin/env bash
# Shared settings for the Atlas Hybrid runtime scripts. Override any of them from the environment.

set -euo pipefail

ATLAS_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# GlassFish installation root (the directory containing bin/asadmin)
GLASSFISH_HOME="${GLASSFISH_HOME:?Set GLASSFISH_HOME to the GlassFish 8 installation (the directory with bin/asadmin)}"
DOMAIN="${DOMAIN:-domain1}"
DOMAIN_DIR="${DOMAIN_DIR:-$GLASSFISH_HOME/glassfish/domains/$DOMAIN}"
HTTP_URL="${HTTP_URL:-http://localhost:8080}"

ASADMIN="$GLASSFISH_HOME/bin/asadmin"
RUNTIME_BUNDLES="$ATLAS_HOME/target/runtime-bundles"
VERSION="0.1.0-SNAPSHOT"

# Declarative Services runtime. GlassFish 8 ships Felix but not SCR; order matters (dependencies first).
SCR_ARTIFACTS=(
    "org.osgi:org.osgi.util.function:1.2.0"
    "org.osgi:org.osgi.util.promise:1.3.0"
    "org.osgi:org.osgi.service.component:1.5.1"
    "org.apache.felix:org.apache.felix.scr:2.2.12"
)

asadmin() {
    local output status=0
    output="$("$ASADMIN" "$@" 2>&1)" || status=$?
    printf '%s\n' "$output" | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
    return "$status"
}

log() {
    printf '\n\033[1;34m==> %s\033[0m\n' "$*"
}

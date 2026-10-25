#!/usr/bin/env bash
# End-to-end scenario of the PoC, with assertions. It installs, replaces and removes plugins while the
# Jakarta EE application keeps running, and checks what the application answers at every step.
# Requires deploy.sh to have run. Exits non-zero on the first failed expectation.

source "$(dirname "$0")/env.sh"

API="$HTTP_URL/atlas/api/greetings"
PLUGIN_EN="$ATLAS_HOME/atlas-hybrid-plugin-en/target/atlas-hybrid-plugin-en-$VERSION.jar"
PLUGIN_ES="$ATLAS_HOME/atlas-hybrid-plugin-es/target/atlas-hybrid-plugin-es-$VERSION.jar"
failures=0

# expect <description> <path> <http status> <substring expected in body>
expect() {
    local description="$1" path="$2" status="$3" needle="$4" body code
    body="$(curl -s -w '\n%{http_code}' "$API/$path")"
    code="${body##*$'\n'}"
    body="${body%$'\n'*}"
    if [[ "$code" == "$status" && "$body" == *"$needle"* ]]; then
        printf '  \033[32mOK\033[0m   %-55s [%s] %s\n' "$description" "$code" "$body"
    else
        printf '  \033[31mFAIL\033[0m %-55s expected [%s] containing "%s", got [%s] %s\n' "$description" "$status" "$needle" "$code" "$body"
        failures=$((failures + 1))
    fi
}

log "0. Initial state: only the Spanish plugin (makes the scenario repeatable)"
asadmin undeploy atlas-plugin-en > /dev/null 2>&1 || true
asadmin deploy --force=true --type=osgi --name=atlas-plugin-es "$PLUGIN_ES" > /dev/null

log "1. Only the Spanish plugin is installed"
expect "default contract served by greeting-es" "Gabriel" 200 '"pluginId":"greeting-es"'
expect "plugins endpoint lists one plugin" "plugins" 200 '"atlas.plugin.id":"greeting-es"'

log "2. Hot-install the English plugin (higher service.ranking), no application redeploy"
asadmin deploy --force=true --type=osgi --name=atlas-plugin-en "$PLUGIN_EN"
expect "higher ranking plugin now serves the contract" "Gabriel" 200 '"pluginId":"greeting-en"'
expect "filtered injection point still gets greeting-es" "es/Gabriel" 200 '"pluginId":"greeting-es"'

log "3. Remove the English plugin: automatic fallback"
asadmin undeploy atlas-plugin-en
expect "falls back to greeting-es" "Gabriel" 200 '"pluginId":"greeting-es"'

log "4. Remove every plugin: the application degrades gracefully"
asadmin undeploy atlas-plugin-es
expect "no plugin: HTTP 503 instead of an error page" "Gabriel" 503 '"error":"plugin-unavailable"'
expect "plugins endpoint is empty" "plugins" 200 '[]'

log "5. Late arrival: a call with timeout=5000 ms waits while the plugin is being deployed"
asadmin deploy --force=true --type=osgi --name=atlas-plugin-es "$PLUGIN_ES" > /dev/null &
deployer=$!
expect "request issued before the plugin exists still succeeds" "es/Gabriel" 200 '"pluginId":"greeting-es"'
wait "$deployer"
expect "plugin back, application never redeployed" "Gabriel" 200 '"pluginId":"greeting-es"'

log "Result"
if (( failures > 0 )); then
    echo "  $failures expectation(s) failed"
    exit 1
fi
echo "  All expectations met"

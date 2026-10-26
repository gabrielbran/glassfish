#!/usr/bin/env bash
# Read-only security audit of a GlassFish domain's secret storage (see docs/security-password-aliases.md).
# Exits non-zero if any check fails. Never prints secret values.

source "$(dirname "$0")/env.sh"

CONFIG="$DOMAIN_DIR/config"
failures=0

ok()   { printf '  \033[32mOK\033[0m    %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; failures=$((failures + 1)); }
warn() { printf '  \033[33mWARN\033[0m  %s\n' "$*"; }

log "Domain $DOMAIN_DIR"

# 1. Default master password: keystore.p12 opens with "changeit" -> every alias is readable from a file copy
if keytool -list -storetype PKCS12 -keystore "$CONFIG/keystore.p12" -storepass changeit > /dev/null 2>&1; then
    fail "master password is the default 'changeit': a copy of domain-passwords.p12 reveals every alias"
else
    ok "master password is not the default"
fi

# 2. Saved master password: protected by a constant hard-coded in GlassFish ("master-password")
if [[ -f "$DOMAIN_DIR/master-password.p12" || -f "$DOMAIN_DIR/master-password" ]]; then
    fail "master password saved on disk (--savemasterpassword): reading that file reveals it; supply it at start instead"
else
    ok "master password not saved on disk"
fi

# 3. Permissions: GlassFish rewrites the alias store with the process umask (findings H-9)
for file in domain-passwords.p12 keystore.p12 cacerts.p12; do
    [[ -f "$CONFIG/$file" ]] || continue
    perms="$(stat -c '%a' "$CONFIG/$file")"
    if [[ "$perms" == "600" || "$perms" == "400" ]]; then
        ok "$file permissions $perms"
    else
        fail "$file permissions $perms (expected 600): run the server with umask 077 and chmod 600 the file"
    fi
done
for leftover in "$CONFIG"/*.save; do
    [[ -e "$leftover" ]] && warn "leftover backup copy $(basename "$leftover") (old keystore content): delete it"
done

# 4. Version: CVE-2026-2586 (fixed in 8.0.2) and CVE-2026-12605 (fixed in 8.0.4) lead to domain takeover
version="$(unzip -p "$GLASSFISH_HOME/glassfish/modules/glassfish-api.jar" META-INF/MANIFEST.MF 2>/dev/null \
    | tr -d '\r' | awk -F': ' '/^Bundle-Version/ {print $2}')"
if [[ -z "$version" ]]; then
    warn "could not determine the GlassFish version"
elif [[ "$(printf '%s\n8.0.4\n' "$version" | sort -V | head -1)" == "8.0.4" ]]; then
    ok "GlassFish $version (>= 8.0.4)"
else
    fail "GlassFish $version is affected by CVE-2026-2586 and/or CVE-2026-12605: upgrade to >= 8.0.4"
fi

log "Result"
if (( failures > 0 )); then
    echo "  $failures check(s) failed"
    exit 1
fi
echo "  All checks passed"

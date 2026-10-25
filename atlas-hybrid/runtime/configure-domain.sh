#!/usr/bin/env bash
# One-time domain preparation for hybrid Jakarta EE + OSGi applications. Run it with the domain started.
#
# GlassFish 8 declares the osgi.ee=JavaSE capability but not the JavaSE/compact1..3 profiles, which every
# bundle published by the OSGi Alliance requires (org.osgi.util.function, promise, the DS API...).
#
# GlassFish lets system properties override any key of its osgi.properties, so we add a JVM option that
# redefines org.osgi.framework.system.capabilities as: the value GlassFish computes for the running Java
# version + the compact profiles. The installation stays untouched (see docs/adr/ADR-004).
#
# Notes:
#  - asadmin strips double quotes from JVM options, so the capabilities are written in a quote-free
#    form: one clause per version instead of version:List<Version>="...".
#  - A domain level config/osgi.properties would be the natural alternative, but GlassFish 8.0.5 fails
#    to start when that file exists (missing bootstrap LogMessages resource bundle). See docs/findings.md.
#  - Re-run after a GlassFish or JDK upgrade: the option is derived from the installation and JDK in use.

source "$(dirname "$0")/env.sh"

PROPERTY="org.osgi.framework.system.capabilities"
JAVA_BIN="${AS_JAVA:+$AS_JAVA/bin/}java"
JAVA_SPEC="$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.vm.specification.version/ {print $2}')"

log "Computing $PROPERTY for Java $JAVA_SPEC from $GLASSFISH_HOME/glassfish/config/osgi.properties"
CAPABILITIES="$(python3 -I - "$GLASSFISH_HOME/glassfish/config/osgi.properties" "eecap-$JAVA_SPEC" <<'EOF'
import re
import sys

# Minimal java.util.Properties reader: comments, "key=value" and backslash continuations.
props, key, value = {}, None, ""
for raw in open(sys.argv[1], encoding="utf-8").read().splitlines():
    line = raw.strip()
    if key is None:
        if not line or line.startswith(("#", "!")) or "=" not in line:
            continue
        key, value = (part.strip() for part in line.split("=", 1))
    else:
        value += line
    if value.endswith("\\"):
        value = value[:-1]
        continue
    props[key], key = value, None

if sys.argv[2] not in props:
    sys.exit(f"{sys.argv[2]} not found: this GlassFish does not support the running Java version")

clauses = []
for clause in props[sys.argv[2]].split(", osgi.ee"):
    name = re.search(r'osgi\.ee="([^"]+)"', clause).group(1)
    versions = re.search(r'version:List<Version>="([^"]+)"', clause)
    if versions:
        clauses += [f"osgi.ee;osgi.ee={name};version:Version={v}" for v in versions.group(1).split(",")]
    else:
        clauses.append(f"osgi.ee;osgi.ee={name}")
clauses += [f"osgi.ee;osgi.ee=JavaSE/compact{n};version:Version=1.8" for n in (1, 2, 3)]
print(",".join(clauses))
EOF
)"

# Replace a previous value if any, then add the new one (colons must be escaped for asadmin).
for previous in $(asadmin list-jvm-options | grep -o -- "-D$PROPERTY=[^ ]*" || true); do
    asadmin delete-jvm-options -- "${previous//:/\\:}"
done
asadmin create-jvm-options -- "-D$PROPERTY=${CAPABILITIES//:/\\:}"

log "Fetching Declarative Services runtime bundles into $RUNTIME_BUNDLES"
mkdir -p "$RUNTIME_BUNDLES"
for artifact in "${SCR_ARTIFACTS[@]}"; do
    mvn -B -q -f "$ATLAS_HOME/pom.xml" -N dependency:copy -Dartifact="$artifact" -DoutputDirectory="$RUNTIME_BUNDLES" \
        2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
done
ls -1 "$RUNTIME_BUNDLES"

log "Restarting the domain so the OSGi framework picks up the new capabilities"
asadmin restart-domain "$DOMAIN"

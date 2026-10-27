#!/usr/bin/env bash
# Deploys, in dependency order: the DS runtime, the contract bundle, the Spanish plugin and the demo WAR.
# Requires a running domain prepared with configure-domain.sh and a previous "mvn install".

source "$(dirname "$0")/env.sh"

deploy_bundle() {
    local name="$1" file="$2"
    asadmin deploy --force=true --type=osgi --name="$name" "$file"
}

log "Declarative Services runtime"
for artifact in "${SCR_ARTIFACTS[@]}"; do
    IFS=: read -r _group id version <<< "$artifact"
    deploy_bundle "$id" "$RUNTIME_BUNDLES/$id-$version.jar"
done

log "Contract bundle (must be deployed before any application that uses it)"
deploy_bundle atlas-greeting-api "$ATLAS_HOME/atlas-hybrid-greeting-api/target/atlas-hybrid-greeting-api-$VERSION.jar"

log "Plugin: greeting-es"
deploy_bundle atlas-plugin-es "$ATLAS_HOME/atlas-hybrid-plugin-es/target/atlas-hybrid-plugin-es-$VERSION.jar"

log "Secrets: contract + GlassFish password alias adapter"
deploy_bundle atlas-secrets-api "$ATLAS_HOME/atlas-hybrid-secrets-api/target/atlas-hybrid-secrets-api-$VERSION.jar"
deploy_bundle atlas-plugin-secrets-glassfish "$ATLAS_HOME/atlas-hybrid-plugin-secrets-glassfish/target/atlas-hybrid-plugin-secrets-glassfish-$VERSION.jar"

log "Plugin framework: contracts, RestClient 1.0.0 and 2.0.0 side by side, Database, Orchestrator"
asadmin start-database > /dev/null 2>&1 || true   # Derby for the demo flows (jdbc/__default)
deploy_bundle atlas-plugin-api "$ATLAS_HOME/atlas-plugin-api/target/atlas-plugin-api-$VERSION.jar"
deploy_bundle atlas-flow-api "$ATLAS_HOME/atlas-flow-api/target/atlas-flow-api-$VERSION.jar"
deploy_bundle atlas-plugin-restclient-1.0.0 "$ATLAS_HOME/atlas-plugin-restclient/target/atlas-plugin-restclient-$VERSION.jar"
deploy_bundle atlas-plugin-restclient-2.0.0 "$ATLAS_HOME/atlas-plugin-restclient-v2/target/atlas-plugin-restclient-v2-$VERSION.jar"
deploy_bundle atlas-plugin-database-1.0.0 "$ATLAS_HOME/atlas-plugin-database/target/atlas-plugin-database-$VERSION.jar"
deploy_bundle atlas-plugin-orchestrator-1.0.0 "$ATLAS_HOME/atlas-plugin-orchestrator/target/atlas-plugin-orchestrator-$VERSION.jar"

log "Jakarta EE application"
asadmin deploy --force=true --contextroot=atlas --name=atlas-hybrid-demo "$ATLAS_HOME/atlas-hybrid-demo-war/target/atlas-hybrid-demo.war"

asadmin list-applications

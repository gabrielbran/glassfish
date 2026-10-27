# Guía de operación (runbook)

Procedimientos para instalar, operar y actualizar Atlas Hybrid sobre GlassFish 8. Todos los scripts leen `GLASSFISH_HOME` (el directorio que contiene `bin/asadmin`) y, opcionalmente, `DOMAIN` (por defecto `domain1`) y `HTTP_URL`.

## 1. Requisitos

- Eclipse GlassFish 8.0.x, Java 21+, Maven 3.9+, `curl` y `python3`.
- Acceso a Maven Central para descargar el runtime DS.

## 2. Instalación desde cero

```bash
export GLASSFISH_HOME=/opt/glassfish8
cd atlas-hybrid

mvn install                          # compila y ejecuta los tests unitarios
$GLASSFISH_HOME/bin/asadmin start-domain
runtime/configure-domain.sh          # una sola vez por dominio (reinicia el dominio)
runtime/deploy.sh                    # runtime DS + contrato + plugin + aplicación
runtime/demo.sh                      # escenario de verificación (debe terminar con "All expectations met")
runtime/flows-demo.sh                # escenarios del orquestador (ídem)
```

Comprobaciones:

```bash
asadmin list-applications            # 14 módulos <osgi> + atlas-hybrid-demo <web>
asadmin osgi lb | grep -iE "atlas|Declarative"   # todos en estado Active
curl http://localhost:8080/atlas/api/greetings/plugins
```

## 3. Operaciones habituales

| Operación | Comando | ¿Redesplegar la aplicación? |
|---|---|---|
| Instalar un plugin | `asadmin deploy --type=osgi --name=<id> plugin.jar` | No |
| Actualizar un plugin | `asadmin deploy --force=true --type=osgi --name=<id> plugin.jar` | No |
| Retirar un plugin | `asadmin undeploy <id>` | No |
| Ver los plugins activos de un contrato | `GET /atlas/api/greetings/plugins` (endpoint de diagnóstico) | — |
| Ver el estado de los bundles | `asadmin osgi lb` | — |
| Actualizar la aplicación | `asadmin deploy --force=true --contextroot=atlas --name=atlas-hybrid-demo app.war` | — |

## 4. Secretos de aplicación (password alias)

Las aplicaciones guardan sus secretos como alias con el prefijo `atlas.` (ADR-008). Desde `asadmin` se gestionan igual que cualquier alias:

```bash
asadmin list-password-aliases                         # los de las apps empiezan por "atlas."
asadmin --passwordfile pw.txt create-password-alias atlas.crm.api-key   # pw.txt: AS_ADMIN_ALIASPASSWORD=...
asadmin delete-password-alias atlas.crm.api-key
```

- Un secreto de aplicación se puede usar en la configuración del dominio: `${ALIAS=atlas.crm.api-key}`.
- Cambiar el prefijo: configuración OSGi con PID `org.atlas.hybrid.secrets.glassfish` y propiedad `atlas.secrets.prefix`. **No** renombra los alias existentes.
- En clúster: `update-password-alias` solo se ejecuta en el DAS (findings H-7). Tras una rotación, verificar las instancias o resincronizarlas.
- **Antes de producción:** `runtime/check-security.sh` debe pasar sin fallos (master password no por defecto ni guardada en disco, permisos `600`, GlassFish ≥ 8.0.4). Ver [security-password-aliases.md](security-password-aliases.md).
- Copia de seguridad: los alias viven en `config/domain-passwords.p12` del dominio y se cifran con la *master password*. Inclúyelos en el backup del dominio junto con esa contraseña.

## 5. Actualizar un contrato (procedimiento obligatorio)

Los contratos **no** se actualizan en caliente (ADR-003). Orden:

1. `asadmin deploy --force=true --type=osgi --name=atlas-greeting-api greeting-api.jar`
2. Redesplegar **cada plugin** que implemente el contrato. GlassFish los deja en estado `Installed` (H-3).
3. Redesplegar **cada aplicación** que lo consuma.
4. Verificar con `asadmin osgi lb` y el endpoint de diagnóstico.

Entre los pasos 1 y 3, las aplicaciones afectadas responden **503** con el mensaje *"Contract bundle … was uninstalled or replaced. Redeploy the application…"*. Conviene planificarlo en una ventana de mantenimiento o desplegar el contrato con un nombre de paquete nuevo (versión mayor).

## 6. Actualizar GlassFish o el JDK

1. Instalar la nueva versión de GlassFish.
2. Ejecutar de nuevo `runtime/configure-domain.sh`, porque la opción `org.osgi.framework.system.capabilities` depende de la versión de Java y de GlassFish (ADR-005).
3. `runtime/deploy.sh` y `runtime/demo.sh`.
4. Si upstream ya incluye los perfiles `compact` (propuesta P-1), eliminar la opción:

   ```bash
   asadmin list-jvm-options | grep system.capabilities
   asadmin delete-jvm-options -- '<opción con los ":" escapados como "\:">'
   ```

## 7. Diagnóstico

| Síntoma | Causa probable | Acción |
|---|---|---|
| `missing requirement osgi.ee ... JavaSE/compact1` al desplegar el runtime DS | Dominio sin preparar | `runtime/configure-domain.sh` |
| `missing requirement osgi.extender ... osgi.component` al desplegar un plugin | Runtime DS no desplegado o inactivo | `runtime/deploy.sh`; `asadmin osgi lb` |
| El dominio no arranca: `MissingResourceException ... bootstrap.log.LogMessages` | Existe `<dominio>/config/osgi.properties` (bug H-2 de GlassFish 8.0.5) | Borrar ese fichero y usar la opción JVM (ADR-005) |
| Respuesta 503 *"No OSGi service available"* | No hay ningún plugin activo para el contrato o filtro | Desplegar o iniciar el plugin; revisar `asadmin osgi lb` |
| Respuesta 503 *"... was not loaded from an OSGi bundle"* | La aplicación empaqueta el contrato en `WEB-INF/lib` | Declarar el contrato con `scope provided` (ADR-003) |
| Respuesta 503 *"... uninstalled or replaced"* | Se actualizó el contrato | Procedimiento de la sección 5 |
| Plugin en estado `Installed` en `osgi lb` | Su contrato fue redesplegado o falta una dependencia | Redesplegar el plugin; el log del servidor indica la dependencia que falta |

Trazas útiles en `server.log`:

- `Atlas bridge: @OsgiService bean registered for ...`: puntos de inyección detectados al desplegar.
- `Atlas bridge: OSGi service trackers closed`: cierre correcto de la aplicación.
- `Atlas plugin <id> <versión> activated/deactivated`: ciclo de vida de los plugins.

Para más detalle del puente: `asadmin set-log-levels org.atlas.hybrid.bridge=FINE`.

## 8. Desinstalación

```bash
asadmin undeploy atlas-hybrid-demo
for m in atlas-plugin-orchestrator-1.0.0 atlas-plugin-database-1.0.0 atlas-plugin-restclient-2.0.0 atlas-plugin-restclient-1.0.0 atlas-flow-api atlas-plugin-api atlas-plugin-secrets-glassfish atlas-secrets-api atlas-plugin-en atlas-plugin-es atlas-greeting-api org.apache.felix.scr \
         org.osgi.service.component org.osgi.util.promise org.osgi.util.function; do
  asadmin undeploy "$m" || true
done
# Opcional: eliminar la opción JVM de capabilities (sección 6.4)
```

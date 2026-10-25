# Resultados y hallazgos de la PoC

- **Fecha:** 2026-10-25
- **Entorno:** Eclipse GlassFish **8.0.5** (distribución oficial de Maven Central, sin modificar) · OpenJDK 21.0.12 · Apache Felix 7.0.5 · Linux
- **Código:** `atlas-hybrid/` en la rama `claude/glassfish-fork-maintenance-plan-ylq07x`

## 1. Hipótesis y resultado

| # | Hipótesis | Resultado |
|---|---|---|
| 1 | Una WAR Jakarta EE 11 estándar puede usar clases exportadas por un bundle OSGi desplegado con `asadmin deploy --type=osgi` | ✅ **Confirmada.** El *API exporter* de GlassFish (`DynamicImport-Package: *`) las hace visibles |
| 2 | Una extensión CDI portable puede inyectar servicios OSGi en beans de la aplicación | ✅ Confirmada (Weld en GlassFish 8.0.5) |
| 3 | Los plugins se pueden instalar, sustituir y retirar sin redesplegar la aplicación | ✅ Confirmada (demo, pasos 2 a 5) |
| 4 | Se respeta `service.ranking` y los filtros LDAP por punto de inyección | ✅ Confirmada (paso 2: `greeting-en` gana; la inyección filtrada sigue en `greeting-es`) |
| 5 | La aplicación degrada de forma controlada sin plugins | ✅ Confirmada: HTTP 503 con `Retry-After` |
| 6 | Declarative Services funciona en GlassFish 8 | ✅ Sí, desplegando Felix SCR 2.2.12 y ajustando la configuración del dominio (H-1) |
| 7 | El contrato se puede actualizar en caliente | ❌ **No, por diseño de OSGi** (enlace del import dinámico). Hay que redesplegar los dependientes (H-3, ADR-003) |
| 8 | El cierre de la aplicación libera los recursos OSGi | ✅ El log muestra *"Atlas bridge: OSGi service trackers closed"* en cada redespliegue o retirada de la WAR |

## 2. Ejecución de la demo (GlassFish 8.0.5)

Salida resumida de `runtime/demo.sh`, ejecutada dos veces seguidas con el mismo resultado:

```
==> 1. Only the Spanish plugin is installed
  OK   default contract served by greeting-es       [200] {"message":"¡Hola, Gabriel!","pluginId":"greeting-es",...}
  OK   plugins endpoint lists one plugin            [200] [{"bundle":"atlas-hybrid-plugin-es",...,"ranking":10,...}]
==> 2. Hot-install the English plugin (higher service.ranking), no application redeploy
  OK   higher ranking plugin now serves the contract [200] {"message":"Hello, Gabriel!","pluginId":"greeting-en",...}
  OK   filtered injection point still gets greeting-es [200] {"message":"¡Hola, Gabriel!","pluginId":"greeting-es",...}
==> 3. Remove the English plugin: automatic fallback
  OK   falls back to greeting-es                    [200]
==> 4. Remove every plugin: the application degrades gracefully
  OK   no plugin: HTTP 503 instead of an error page [503] {"error":"plugin-unavailable",...}
  OK   plugins endpoint is empty                    [200] []
==> 5. Late arrival: a call with timeout=5000 ms waits while the plugin is being deployed
  OK   request issued before the plugin exists still succeeds [200]
  OK   plugin back, application never redeployed    [200]
  All expectations met
```

Tiempos orientativos: despliegue completo (`deploy.sh`, 7 artefactos) ≈ 9 s; escenario completo (`demo.sh`) ≈ 3 s, la mayor parte en arrancar el cliente `asadmin`.

## 3. Hallazgos

### H-1. GlassFish 8 no declara los perfiles `osgi.ee=JavaSE/compact1..3` (carencia de GlassFish)

- **Síntoma:** `Unable to resolve org.osgi.util.function: missing requirement osgi.ee; (&(osgi.ee=JavaSE/compact1)(version=1.8))`. Afecta a cualquier bundle de la OSGi Alliance (DS API, promise, function…).
- **Causa:** las entradas `eecap-*` de `glassfish/config/osgi.properties` (origen: `nucleus/distributions/nucleus-common/src/main/resources/config/osgi.properties`) omiten los perfiles `compact`, que el Felix estándar sí incluye.
- **Solución en Atlas:** opción JVM en el dominio (ADR-005).
- **Propuesta upstream P-1:** añadir `osgi.ee; osgi.ee="JavaSE/compact1|2|3"; version:List<Version>="1.8"` a cada `eecap-N` con N ≥ 1.8. Es un cambio de configuración pequeño y de bajo riesgo.

### H-2. GlassFish 8.0.5 no arranca si existe `<dominio>/config/osgi.properties` (bug de GlassFish)

- **Síntoma:** `start-domain` falla con `ExceptionInInitializerError` → `MissingResourceException: Can't find com.sun.enterprise.glassfish.bootstrap.log.LogMessages bundle`, lanzada desde `StartupContextCfgFactory.getFrameworkConfigFile` (línea 125).
- **Causa:** `LogFacade` declara `RB_NAME = "...bootstrap.log.LogMessages"` (generado por el procesador de anotaciones de logging), pero `lib/bootstrap/glassfish.jar` 8.0.5 **no contiene `LogMessages.properties`**. El primer uso de `BOOTSTRAP_LOGGER` aborta el arranque. Con la configuración por defecto no se llega a ese log, por eso pasa desapercibido.
- **Impacto:** inutiliza el mecanismo documentado de configuración OSGi por dominio y cualquier otra ruta que registre con ese logger.
- **Estado:** observado en 8.0.5; no verificado contra `main`.
- **Propuesta upstream P-2:** abrir un issue con la reproducción (crear `domains/domain1/config/osgi.properties` copiando el de la instalación y ejecutar `asadmin start-domain`) y revisar el empaquetado del recurso en `nucleus/core/bootstrap`.

### H-3. Al redesplegar un contrato, el tracker deja de recibir eventos: servicio "zombi" (defecto del puente, corregido)

- **Síntoma inicial:** tras `asadmin deploy --force --type=osgi` del contrato, `osgi lb` mostraba el plugin en estado `Installed` (detenido), pero la aplicación **seguía respondiendo con él**.
- **Causa:** el `ServiceTracker` usaba el `BundleContext` del bundle de contrato antiguo. Al pararse ese bundle, Felix elimina sus listeners: el tracker no recibe el `UNREGISTERING` y sigue devolviendo de su caché el objeto del plugin detenido.
- **Corrección:** el registro valida el contexto antes de cada uso (`BundleContext.getBundle()` lanza `IllegalStateException` si ya no es válido). Si no lo es, reabre el tracker; si el contrato con el que está enlazada la aplicación fue reemplazado, responde: *"Contract bundle … was uninstalled or replaced. Redeploy the application so it links against the current contract."*
- **Prueba de regresión:** `OsgiServiceRegistryTest.stoppedContractBundleNeverServesStaleServices`. Se comprobó que **falla sin la corrección** y pasa con ella.
- **Comportamiento de GlassFish observado:** tras redesplegar un contrato, los plugins dependientes quedan en `Installed` y no se reinician solos. Hay que redesplegarlos (procedimiento en [operations.md](operations.md)).

### H-4. `asadmin create-jvm-options` elimina las comillas dobles

Esto impide usar la sintaxis `version:List<Version>="1.8,9,..."`. Solución: una cláusula por versión (`version:Version=21`), semánticamente equivalente.

## 4. Riesgos abiertos

| Riesgo | Mitigación prevista |
|---|---|
| Concurrencia durante la sustitución de un plugin bajo carga | Prueba de carga en fase 1 (llamadas concurrentes mientras se hace `deploy`/`undeploy`) |
| Evolución de contratos sin disciplina semver | `bnd-baseline-maven-plugin` en CI (fase 1) |
| Dependencia de tres comportamientos internos de GlassFish (API exporter, override por propiedades de sistema, deployer OSGi) | Ejecutar la demo en CI contra cada nueva versión de GlassFish antes de adoptarla |
| Clústeres e instancias remotas no probados | Validar `deploy --target <cluster>` con bundles en fase 1 |

## 5. Conclusión

El enfoque es **viable sobre GlassFish 8 sin modificar el servidor**. El puente es pequeño (unas 330 líneas de código), solo usa API estándar (CDI 4.1, OSGi core) y cubre el caso de valor: aplicaciones Jakarta EE estables con capacidades de negocio enchufables en caliente. Las dos carencias de GlassFish encontradas (H-1, H-2) tienen solución por configuración y son buenas candidatas para contribuir upstream.

# Atlas Hybrid: aplicaciones Jakarta EE 11 + plugins OSGi en GlassFish 8

Prueba de concepto de una **línea de integración híbrida**: aplicaciones Jakarta EE 11 estándar cuyas capacidades de negocio las aportan **plugins OSGi que se instalan, sustituyen y retiran en caliente**, sin redesplegar la aplicación y **sin modificar GlassFish**.

```java
@Inject @AtlasPlugin(name = "orchestrator", version = "1.0.0")
FlowEngine engine;                   // motor de flujos: plugins orquestados desde metadata
engine.run("alta-cliente", input);

@Inject @OsgiService                 // único rastro de OSGi en el código de negocio
GreetingService greetings;

@Inject @Any
Instance<GreetingService> byId;      // selección por id de plugin en tiempo de ejecución
byId.select(PluginId.Literal.of("greeting-en")).get().greet("Ana");
```

> Este directorio es un proyecto Maven **independiente del build de GlassFish** (ADR-001): las fusiones desde upstream nunca lo tocan.

## Estado

✅ Validado sobre la distribución oficial de **Eclipse GlassFish 8.0.5** (Java 21, Felix 7.0.5): 31 tests unitarios y 44 verificaciones extremo a extremo en GlassFish real (`demo.sh` 24, `flows-demo.sh` 20). Ver [docs/findings.md](docs/findings.md).

## Estructura

| Módulo | Qué es |
|---|---|
| `atlas-hybrid-greeting-api` | Contrato de ejemplo (bundle OSGi, paquete versionado) |
| `atlas-hybrid-bridge` | Puente CDI → OSGi (extensión CDI portable, va en `WEB-INF/lib`) |
| `atlas-hybrid-plugin-es`, `atlas-hybrid-plugin-en` | Plugins de ejemplo (componentes Declarative Services, rankings 10 y 20) |
| `atlas-hybrid-secrets-api` | Contrato `SecretStore` para secretos de aplicación |
| `atlas-hybrid-plugin-secrets-glassfish` | Adaptador de plataforma: `SecretStore` sobre los password alias de GlassFish (ADR-008) |
| `atlas-plugin-api` | Contrato común de los plugins de Atlas: `Plugin.execute(PluginRequest)` (ADR-009) |
| `atlas-flow-api` | Contrato del motor de flujos: `FlowEngine`, `FlowResult` |
| `atlas-plugin-restclient`, `-v2` | Plugin `rest-client` **1.0.0 (OkHttp 4.12.0)** y **2.0.0 (OkHttp 5.3.0)**, mismo código, instalables a la vez |
| `atlas-plugin-database` | Plugin `database` 1.0.0: SQL parametrizado dentro de la transacción JTA |
| `atlas-plugin-orchestrator` | Plugin `orchestrator` 1.0.0: flujos en memoria con secuencia, paralelo, disparar y olvidar, reglas, rollback y compensaciones (ADR-010) |
| `atlas-hybrid-demo-war` | Aplicación Jakarta EE 11 de ejemplo (REST) |
| `runtime/` | `configure-domain.sh`, `deploy.sh`, `demo.sh`, `flows-demo.sh`, `check-security.sh` |
| `docs/` | Arquitectura, ADR, hallazgos, operación y guía de plugins |

## Inicio rápido

```bash
export GLASSFISH_HOME=/ruta/a/glassfish8      # directorio que contiene bin/asadmin
mvn install
$GLASSFISH_HOME/bin/asadmin start-domain
runtime/configure-domain.sh                   # una vez por dominio
runtime/deploy.sh
runtime/demo.sh
```

```bash
curl http://localhost:8080/atlas/api/greetings/Ana        # {"message":"¡Hola, Ana!","pluginId":"greeting-es",...}
curl http://localhost:8080/atlas/api/greetings/plugins    # plugins activos, el mejor primero
```

## Documentación

- [Arquitectura](docs/architecture.md)
- [Decisiones de arquitectura (ADR)](docs/adr/README.md)
- [Resultados y hallazgos de la PoC](docs/findings.md)
- [Guía de operación](docs/operations.md)
- [Orquestador en memoria: referencia de la metadata](docs/orchestrator.md)
- [Guía para desarrollar un plugin](docs/plugin-development.md)
- [Seguridad de los password alias](docs/security-password-aliases.md)

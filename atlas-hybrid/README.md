# Atlas Hybrid: aplicaciones Jakarta EE 11 + plugins OSGi en GlassFish 8

Prueba de concepto de una **línea de integración híbrida**: aplicaciones Jakarta EE 11 estándar cuyas capacidades de negocio las aportan **plugins OSGi que se instalan, sustituyen y retiran en caliente**, sin redesplegar la aplicación y **sin modificar GlassFish**.

```java
@Inject @OsgiService                 // único rastro de OSGi en el código de negocio
GreetingService greetings;

@Inject @Any
Instance<GreetingService> byId;      // selección por id de plugin en tiempo de ejecución
byId.select(PluginId.Literal.of("greeting-en")).get().greet("Ana");
```

> Este directorio es un proyecto Maven **independiente del build de GlassFish** (ADR-001): las fusiones desde upstream nunca lo tocan.

## Estado

✅ Validado sobre la distribución oficial de **Eclipse GlassFish 8.0.5** (Java 21, Felix 7.0.5): 11 tests unitarios sobre Felix embebido y 14 verificaciones extremo a extremo en GlassFish real. Ver [docs/findings.md](docs/findings.md).

## Estructura

| Módulo | Qué es |
|---|---|
| `atlas-hybrid-greeting-api` | Contrato de ejemplo (bundle OSGi, paquete versionado) |
| `atlas-hybrid-bridge` | Puente CDI → OSGi (extensión CDI portable, va en `WEB-INF/lib`) |
| `atlas-hybrid-plugin-es`, `atlas-hybrid-plugin-en` | Plugins de ejemplo (componentes Declarative Services, rankings 10 y 20) |
| `atlas-hybrid-demo-war` | Aplicación Jakarta EE 11 de ejemplo (REST) |
| `runtime/` | `configure-domain.sh`, `deploy.sh`, `demo.sh` |
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
- [Guía para desarrollar un plugin](docs/plugin-development.md)

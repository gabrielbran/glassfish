# ADR-004: Puente CDI → OSGi propio y delgado, en lugar de migrar FighterFish o usar OSGi CDI

- **Estado:** Aceptado
- **Fecha:** 2026-10-25

## Contexto

Necesitamos que una aplicación Jakarta EE 11 inyecte servicios OSGi con CDI y tolere que aparezcan y desaparezcan. Las opciones existentes son:

- **FighterFish** (integración híbrida histórica de GlassFish: WAB, EJB-OSGi, `@OSGiService`): sigue en el namespace `javax` (Jakarta EE 8: `javax.servlet`, `javax.enterprise`…) y no se distribuye con GlassFish 8. Migrarlo supone tocar unos 11 módulos.
- **OSGi CDI Integration (R7, cap. 152) / Apache Aries CDI:** pone CDI *dentro* de los bundles, es decir, el caso inverso. No integra una WAR gestionada por GlassFish y tiene poca actividad.
- **Extenders propios anteriores (`osgi.ee`):** de la época EE7 / Java 8, anteriores al estándar.

## Decisión

Construir un **puente propio, mínimo y unidireccional** (la aplicación consume plugins), empaquetado como JAR en `WEB-INF/lib`:

- Una **extensión CDI portable** (estándar Jakarta EE, sin API propietaria de Weld ni de GlassFish).
- `@OsgiService(filter, timeout)` como único punto de contacto en el código de negocio, y `@PluginId` (miembro `@Nonbinding`) para elegir un plugin por id, de forma estática o con `Instance.select(...)`.
- **Proxies dinámicos** respaldados por `ServiceTracker` y compartidos por clave: el servicio se resuelve en cada llamada.
- Validación del `BundleContext` antes de cada uso, para no servir nunca servicios obsoletos.
- Cierre de todos los trackers en `BeforeShutdown`.
- **Límite de complejidad:** si el puente supera unas pocas miles de líneas, hay que revisar este ADR. Hoy son unas 450 líneas de código de producción (unas 720 con comentarios).

La dirección inversa (publicar beans EE como servicios OSGi) y los WAB **no** forman parte del alcance hasta que exista un caso real.

## Alternativas consideradas

| Alternativa | A favor | En contra |
|---|---|---|
| Migrar FighterFish a `jakarta.*` | Funcionalidad completa (WAB, EJB-OSGi) | Proyecto grande, mantenimiento en solitario de mucho código que no necesitamos |
| Aries CDI / OSGi CDI | Estándar OSGi | Resuelve otro problema (CDI dentro de bundles) |
| Extensión dependiente de Weld | Más control | Atada a la implementación |
| **Puente propio con extensión portable** | Pequeño, comprensible, solo API estándar (CDI 4.1 y OSGi core) | Hay que mantenerlo nosotros; solo cubre la dirección EE → OSGi |

## Consecuencias

- **Positivas:** funciona sobre GlassFish 8.0.5 sin cambios en el servidor; probado con 11 tests unitarios sobre Felix embebido y con la demo extremo a extremo.
- **Negativas:** solo procesa puntos de inyección estáticos (para los dinámicos está `OsgiServices.proxy`). Obtener el `BundleContext` a partir del bundle del contrato es una convención de Atlas.
- **Futuro:** si el puente demuestra su valor, puede proponerse a la comunidad GlassFish como sucesor ligero de FighterFish.

## Verificación

- `mvn install`: 11 tests, entre ellos `stoppedContractBundleNeverServesStaleServices`, que reproduce el defecto del servicio zombi.
- `runtime/demo.sh`: 14 verificaciones en GlassFish real.

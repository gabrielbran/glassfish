# Atlas Hybrid: arquitectura de integración Jakarta EE + OSGi

| | |
|---|---|
| **Estado** | Prueba de concepto (PoC) validada sobre GlassFish 8.0.5 |
| **Versión** | 0.1.0-SNAPSHOT |
| **Fecha** | 2026-10-25 |
| **Plataforma** | Eclipse GlassFish 8.0.x · Jakarta EE 11 · Java 21 · Apache Felix 7.0.5 |

## 1. Propósito

Atlas Hybrid define cómo construir **aplicaciones Jakarta EE 11 de capa media cuyas capacidades de negocio se amplían, sustituyen o retiran en caliente mediante plugins OSGi**, sin redesplegar la aplicación y sin hacer un fork de GlassFish.

Lo que se busca es:

- **Dinamismo:** instalar, actualizar o retirar implementaciones de negocio en caliente.
- **Modularidad:** contratos explícitos y versionados entre la aplicación y los plugins.
- **Simplicidad:** el código de negocio es Jakarta EE estándar. OSGi solo asoma en un *qualifier* (`@OsgiService`).
- **Mantenibilidad:** ni una línea modificada en el core de GlassFish, de modo que las actualizaciones de upstream se adoptan sin conflictos.

## 2. Contexto y restricciones

- GlassFish 8 arranca sobre Apache Felix. El runtime OSGi está disponible y el comando `asadmin deploy --type=osgi` instala bundles.
- La antigua integración híbrida de GlassFish (**FighterFish**: WAB, EJB como servicio OSGi, `@OSGiService` en CDI) sigue en el namespace `javax` y **no se distribuye con GlassFish 8**.
- GlassFish 8 **no incluye Declarative Services (SCR)**.
- El bundle *API exporter* de GlassFish (`GlassFish-Application-Common-Module`) declara `DynamicImport-Package: *`. Gracias a eso, **las aplicaciones Jakarta EE pueden ver los paquetes exportados por cualquier bundle**. Es la pieza que hace viable el enfoque, y la PoC lo ha validado.

## 3. Vista general

```
┌─────────────────────── Aplicación Jakarta EE 11 (WAR / EAR estándar) ───────────────────────┐
│  REST · CDI · EJB · JPA · Faces                                                             │
│                                                                                             │
│   @Inject @OsgiService GreetingService greetings;   ← único rastro de OSGi en el negocio    │
│                                                                                             │
│   WEB-INF/lib/atlas-hybrid-bridge.jar   (extensión CDI portable)                            │
└──────────────┬──────────────────────────────────────────────────────────────────────────────┘
               │ proxy dinámico → ServiceTracker (por cada llamada: mejor servicio disponible)
               │ clases del contrato visibles vía API exporter (DynamicImport-Package: *)
┌──────────────▼──────────────────────────────────────────────────────────────────────────────┐
│  Contrato (bundle OSGi)        atlas-hybrid-greeting-api                                    │
│  Export-Package: org.atlas.hybrid.greeting.api;version="1.0.0"                              │
└──────────────▲───────────────────────────────▲──────────────────────────────────────────────┘
               │ implementa                    │ implementa
┌──────────────┴───────────────┐ ┌─────────────┴────────────────┐
│ Plugin (bundle, componente   │ │ Plugin (bundle, componente   │   ← instalables / retirables
│ DS) greeting-es · ranking 10 │ │ DS) greeting-en · ranking 20 │      en caliente
└──────────────────────────────┘ └──────────────────────────────┘
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│  Runtime: Felix SCR 2.2.12 + OSGi DS API 1.5.1 + promise 1.3 + function 1.2  (bundles)      │
├─────────────────────────────────────────────────────────────────────────────────────────────┤
│  Eclipse GlassFish 8.0.x sin modificar  ·  Apache Felix 7.0.5  ·  HK2  ·  Java 21           │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

## 4. Componentes

| Módulo | Tipo | Dónde se despliega | Responsabilidad |
|---|---|---|---|
| `atlas-hybrid-greeting-api` | Bundle OSGi | `asadmin deploy --type=osgi` | Contrato de negocio de ejemplo. Solo interfaces y tipos de valor, sin dependencias. Paquete versionado (`@Version("1.0.0")`). |
| `atlas-hybrid-bridge` | JAR (no bundle) | `WEB-INF/lib` de cada aplicación | Extensión CDI portable: `@OsgiService`, proxies dinámicos, `OsgiServices`, `ServiceUnavailableException`. |
| `atlas-hybrid-plugin-es` / `-en` | Bundles OSGi con componentes DS | `asadmin deploy --type=osgi` | Implementaciones del contrato. Se registran y retiran solas con SCR. |
| `atlas-hybrid-demo-war` | WAR Jakarta EE 11 | `asadmin deploy` | Aplicación de ejemplo: REST, mapeo de "plugin no disponible" a HTTP 503 y endpoint de diagnóstico. |
| `runtime/*.sh` | Scripts | Administración | Preparación del dominio, despliegue ordenado y escenario de demo con verificaciones. |

## 5. El puente CDI ↔ OSGi

### 5.1 Uso

```java
@Inject @OsgiService
GreetingService greetings;                       // mejor servicio (mayor service.ranking)

@Inject @OsgiService(filter = "(atlas.plugin.id=greeting-es)", timeout = 5000)
GreetingService spanish;                         // filtro LDAP + espera de hasta 5 s

@Inject @PluginId("greeting-en")
GreetingService english;                         // un plugin concreto por su atlas.plugin.id

@Inject @Any
Instance<GreetingService> byId;                  // plugin elegido en tiempo de ejecución (p. ej. desde la petición)
GreetingService chosen = byId.select(PluginId.Literal.of(id)).get();

@Inject
OsgiServices osgi;                               // diagnóstico y acceso programático
List<ServiceDescriptor> plugins = osgi.describe(GreetingService.class);
GreetingService same = osgi.byPluginId(GreetingService.class, id);
```

`@PluginId` compara el id **literalmente** con la propiedad `atlas.plugin.id`. No construye un filtro LDAP con él, así que es seguro con ids que vienen del usuario, y reutiliza un único tracker por contrato, de modo que pedir ids distintos no hace crecer nada.

### 5.2 Funcionamiento

1. **Arranque de la aplicación.** La extensión observa `ProcessInjectionPoint` y recoge cada combinación distinta `(tipo, filtro, timeout)` cualificada con `@OsgiService`. En `AfterBeanDiscovery` registra un bean sintético `@Dependent` por cada una. En este punto no se toca el framework OSGi.
2. **`@PluginId`.** Por cada contrato usado en un `Instance<Contrato>` o en un `@PluginId Contrato` **declarado en clases de la aplicación**, se registra un único bean `@PluginId` (miembro `@Nonbinding`) que lee el id solicitado de su `InjectionPoint`. Weld informa de los qualifiers elegidos con `Instance.select(...)`, comprobado en GlassFish 8.0.5. Los puntos de inyección de las propias librerías de GlassFish se ignoran (findings H-5).
3. **Inyección.** El bean devuelve un **proxy dinámico compartido** (`java.lang.reflect.Proxy`) del contrato. Al no haber client proxy de Weld, no se generan clases en el class loader del bundle.
4. **Primera llamada.** Se abre un `ServiceTracker` usando el `BundleContext` del bundle que cargó el contrato (`FrameworkUtil.getBundle(contrato)`). Hay un tracker por clave, no por instancia de bean.
5. **Cada llamada.** El proxy obtiene el mejor servicio disponible en ese momento y delega en él. Si no hay ninguno, falla de inmediato con `ServiceUnavailableException` o espera hasta `timeout` ms.
6. **Contexto invalidado.** Si el bundle de contrato se detiene, actualiza o desinstala, Felix elimina sus listeners y el tracker dejaría de enterarse de los cambios. Por eso el puente **valida el contexto antes de cada uso** y reabre el tracker si es posible. Si el contrato con el que está enlazada la aplicación ya no existe, devuelve un error claro: "redespliega la aplicación". Este caso lo detectó la PoC (ver [findings.md](findings.md)).
7. **Parada de la aplicación.** `BeforeShutdown` cierra todos los trackers, para no dejar listeners colgados ni retener el class loader de la aplicación.

### 5.3 Garantías y límites

| Garantía | Límite conocido |
|---|---|
| Los plugins se instalan, reemplazan y retiran sin redesplegar la aplicación. | **Cambiar el contrato** exige redesplegar los plugins y las aplicaciones que lo usan (ver ADR-003). |
| Se elige siempre el servicio de mayor `service.ranking`; a igual ranking, el de menor `service.id`. | `Instance.select(...)` solo admite `@PluginId`. Para filtros LDAP arbitrarios en tiempo de ejecución está `OsgiServices.proxy(...)`, solo con valores de confianza. |
| Las excepciones del plugin llegan sin envolver al llamante. | Una llamada en curso puede terminar en un plugin que se está deteniendo, como en cualquier sistema OSGi. |
| La aplicación arranca aunque no haya plugins; las llamadas devuelven 503. | El contrato debe estar desplegado **antes** que la aplicación, porque se necesitan sus clases para desplegar la WAR. |
| Ni el código de negocio ni los contratos dependen de OSGi. | El puente es específico de GlassFish/Felix en la forma de obtener el `BundleContext`, aunque solo usa API estándar de OSGi. |

## 6. Responsabilidad de cada tecnología

| Tecnología | Se usa para | No se usa para |
|---|---|---|
| **Jakarta EE (CDI, REST, EJB, JPA, JTA)** | Modelo de programación de las aplicaciones y servicios de plataforma | — |
| **OSGi + Declarative Services** | Plugins de negocio dinámicos y contratos versionados | Reemplazar el modelo de componentes de la aplicación |
| **HK2** | Extender el servidor (comandos `asadmin`, configuración, monitorización) | Plugins de negocio, porque ata el código a los internals de GlassFish |
| **JTA / JNDI de GlassFish** | Persistencia de los plugins: `DataSource` por JNDI dentro de la transacción del llamante (ADR-007) | Que los plugins gestionen sus propias transacciones globales |
| **JCA** (próxima fase) | Integración con sistemas externos con contratos de transacción y seguridad | — |

Ver [ADR-002](adr/ADR-002-osgi-ds-para-plugins.md).

## 7. Despliegue

Orden obligatorio, automatizado en `runtime/deploy.sh`:

1. Preparar el dominio una sola vez con `runtime/configure-domain.sh` (ver [ADR-005](adr/ADR-005-configuracion-dominio-sin-tocar-core.md)).
2. Desplegar el runtime DS: `function` → `promise` → `service.component` → `felix.scr`.
3. Desplegar los contratos.
4. Desplegar los plugins, en cualquier orden y en cualquier momento.
5. Desplegar las aplicaciones Jakarta EE.

## 8. Calidad y pruebas

| Nivel | Qué cubre | Cómo |
|---|---|---|
| Unitario | Ranking, filtros, selección por id, timeout, excepciones, cierre y contexto invalidado (servicio zombi) | 11 tests con JUnit 5 sobre **Felix 7.0.5 embebido**, el mismo framework que usa GlassFish (`mvn install`) |
| Extremo a extremo | Instalación, sustitución y retirada en caliente, fallback, `@PluginId`, dependencias embebidas, 503 y llegada tardía con timeout | `runtime/demo.sh` contra GlassFish real (14 verificaciones) |
| Pendiente | Baselining de contratos en CI, Arquillian, pruebas de carga y concurrencia durante la sustitución | Ver la hoja de ruta |

## 9. Hoja de ruta

| Fase | Contenido |
|---|---|
| **0. PoC** ✅ | Puente, contrato, 2 plugins, WAR, scripts y validación en GlassFish 8.0.5 |
| **1. Endurecer** | `bnd-baseline-maven-plugin` en los contratos; pipeline de CI que arranque GlassFish y ejecute `demo.sh`; métricas (MicroProfile Metrics / JMX) de llamadas y fallos por contrato; health check de plugins |
| **2. Configuración** | Plugins configurables con Config Admin (`@Designate` + metatype), que GlassFish ya incluye (`org.apache.felix.configadmin`) |
| **3. Operación** | Comandos `asadmin atlas-list-plugins` / `atlas-plugin-info` (HK2) y vista en la consola |
| **4. Persistencia** | Validar JPA dentro de un plugin (EclipseLink con `jtaDataSource`), ver ADR-007 |
| **5. Integración** | Adaptador JCA de Atlas para sistemas externos; publicación opcional de beans EE como servicios OSGi (dirección inversa) solo si hay un caso real |
| **6. Comunidad** | Proponer upstream las correcciones encontradas (ver [findings.md](findings.md)) |

## 10. Documentos relacionados

- [Decisiones de arquitectura (ADR)](adr/README.md)
- [Resultados y hallazgos de la PoC](findings.md)
- [Guía de operación](operations.md)
- [Guía para desarrollar un plugin](plugin-development.md)

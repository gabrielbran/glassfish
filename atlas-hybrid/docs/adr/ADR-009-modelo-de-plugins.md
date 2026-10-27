# ADR-009: Modelo de plugins de Atlas: contrato `Plugin`, `@AtlasPlugin` con versión exacta y dependencias embebidas

- **Estado:** Aceptado
- **Fecha:** 2026-10-27

## Contexto

Atlas necesita plugins con una responsabilidad única (conectar con REST, acceder a base de datos, orquestar…) que:
- se puedan invocar de forma uniforme desde la metadata de Atlas;
- se identifiquen por **nombre y versión exacta**;
- puedan **convivir en varias versiones** del mismo plugin;
- lleven sus **dependencias aisladas** (por ejemplo, OkHttp), fuera del classpath de las aplicaciones y de los demás plugins.

Decisiones del responsable: versión **exacta** (sin rangos) y dependencias **embebidas** en cada plugin (opción A).

## Decisión

1. **Contrato común** `atlas-plugin-api` → `Plugin.execute(PluginRequest) : PluginResult`. Parámetros y resultados son valores JSON (`String`, `Number`, `Boolean`, `List`, `Map`) para que vengan de la metadata y vuelvan a ella. Los errores son `PluginException` con un **código estable** (`TIMEOUT`, `HTTP_ERROR`, `SQL`…).
2. **Identidad en las propiedades del servicio OSGi**, no en código:
   - `atlas.plugin.name`;
   - la **versión** es el `major.minor.micro` del bundle, salvo que `atlas.plugin.version` diga otra cosa;
   - `atlas.plugin.description`;
   - `atlas.plugin.transactional`.
3. **Varias versiones = varios bundles** con el mismo `Bundle-SymbolicName` y distinto `Bundle-Version`, instalados a la vez. El mismo código fuente puede generar varias versiones (RestClient 1.0.0 y 2.0.0 comparten fuente y solo cambia el OkHttp embebido).
4. **Dependencias embebidas en `lib/`** dentro del bundle, listadas en `Bundle-ClassPath` (bnd las enumera solas con `${lsr;…}`). No se exportan. Las integraciones opcionales de la librería (Android, Conscrypt, BouncyCastle…) se importan como `resolution:=optional`.
5. **`@AtlasPlugin(name, version, description)`** en el puente CDI, con **todos sus miembros `@Nonbinding`**: un único bean por contrato, que lee nombre y versión de su `InjectionPoint` en tiempo de ejecución. Con miembros *binding*, CDI exigiría que cada combinación existiera al desplegar y no se podría instalar un plugin después. La versión se valida como exacta (`\d+.\d+.\d+`); la descripción es informativa.
6. **Los plugins son singletons thread-safe**, con un único `OkHttpClient` (y su pool de conexiones) por plugin. No se crean instancias por transacción: el estado de una ejecución vive en el orquestador.

## Alternativas consideradas

| Alternativa | En contra |
|---|---|
| Versión por rangos (`[1.0,2.0)`) | Descartada por decisión del responsable: cada uso declara exactamente qué versión ejecuta |
| Dependencias como bundles compartidos (opción B) | Una sola versión de OkHttp para todos; descartada |
| `-conditionalpackage` (copiar solo los paquetes usados) | Válido para librerías Java simples (ADR anterior, commons-text). Para Kotlin (OkHttp, Okio) es más robusto el JAR completo en `Bundle-ClassPath` |
| Directorio externo con un class loader propio | Se pierde lo que da OSGi: resolución, versiones y descarga del class loader |
| Interfaces tipadas por capacidad (`RestConnector`…) | Útiles para inyección directa, pero el orquestador necesita una entrada uniforme. Se podrán añadir encima de `Plugin` |

## Consecuencias

- Verificado en GlassFish 8.0.5: `rest-client` 1.0.0 (OkHttp 4.12.0, Kotlin 1.8) y 2.0.0 (OkHttp 5.3.0, Kotlin 2.2) activos a la vez, cada uno informando su propia versión de OkHttp en la misma ejecución.
- Cada bundle de RestClient ocupa unos 2,8 MB, porque incluye su Kotlin. Es el precio del aislamiento total.
- **Actualizar una versión es un cambio explícito de metadata.** Retirar una versión que un flujo usa hace fallar ese flujo con un mensaje claro (escenario G); nunca se cambia de versión en silencio.
- Pendiente para la fase duradera: impedir la desinstalación de una versión con ejecuciones activas.

## Verificación

- `OsgiServiceRegistryTest.atlasPluginSelectsByNameAndExactVersion`: dos bundles 1.0.0 y 2.0.0, versión explícita, sin coincidencia por rangos, desinstalación.
- `runtime/flows-demo.sh`, escenarios A, F y G.
- Manifiestos: `Import-Package` de RestClient solo con contrato, OSGi, JSON-P y JDK (resto opcional); sin `Export-Package`.

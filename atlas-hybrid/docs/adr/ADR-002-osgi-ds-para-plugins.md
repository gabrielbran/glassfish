# ADR-002: OSGi + Declarative Services para plugins de negocio; HK2 solo para el servidor

- **Estado:** Aceptado
- **Fecha:** 2026-10-25

## Contexto

GlassFish ofrece varios modelos de componentes: CDI (aplicaciones), HK2 (el propio servidor) y OSGi (el runtime de módulos). Atlas los menciona todos. Usar dos de ellos para lo mismo, por ejemplo plugins de negocio en HK2 y en OSGi a la vez, duplica conceptos, ciclos de vida y formas de fallar.

Dentro de OSGi, los plugins pueden registrar servicios a mano (`BundleActivator`) o de forma declarativa (Declarative Services, DS).

## Decisión

Cada tecnología tiene una sola responsabilidad:

| Tecnología | Responsabilidad |
|---|---|
| **CDI / Jakarta EE** | Modelo de programación de las aplicaciones |
| **OSGi + DS** | Plugins de negocio dinámicos |
| **HK2** | Exclusivamente extensiones del servidor: comandos `asadmin`, configuración, monitorización |

Además:

- Los plugins se escriben como **componentes DS** (`@Component`), sin `BundleActivator`. Las anotaciones DS tienen retención `CLASS`: bnd genera el XML del componente y no dejan dependencia en tiempo de ejecución.
- El runtime DS (Apache Felix SCR 2.2.12 y sus dependencias) se despliega como bundles, porque GlassFish 8 no lo incluye.
- Los plugins se identifican con la propiedad `atlas.plugin.id`, y su prioridad se expresa con `service.ranking`.

## Alternativas consideradas

| Alternativa | A favor | En contra |
|---|---|---|
| Plugins HK2 | Ya incluido, sin bundles extra | Ata el negocio a los internals de GlassFish, sin versionado de paquetes ni sustitución en caliente estándar |
| OSGi con `BundleActivator` | Sin dependencias extra | Código repetitivo, gestión manual de dependencias dinámicas y errores sutiles al parar |
| **OSGi + DS** | Estándar, declarativo, ciclo de vida y dependencias gestionados por SCR, ampliamente probado | Hay que desplegar 4 bundles de runtime |
| Plugins como aplicaciones CDI separadas | Un único modelo | Sin servicios dinámicos entre aplicaciones ni versionado de paquetes |

## Consecuencias

- **Positivas:** los plugins de la PoC no tienen código de infraestructura (solo `@Component`, `@Activate` y `@Deactivate` para trazas); SCR registra y retira los servicios al iniciar y parar el bundle.
- **Negativas:** el runtime DS forma parte de la instalación de Atlas y hay que actualizarlo, y sus bundles necesitan los perfiles `compact` (ver ADR-005).
- **Obligatorio:** los plugins de negocio no usan HK2; las extensiones del servidor no se publican como servicios de negocio.

## Verificación

- `runtime/demo.sh`, pasos 1 a 3: los plugins aparecen y desaparecen únicamente con despliegues OSGi.
- Revisión de código: ningún plugin importa `org.glassfish.hk2` ni `org.jvnet.hk2`.

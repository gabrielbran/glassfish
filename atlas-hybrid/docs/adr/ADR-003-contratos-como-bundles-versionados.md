# ADR-003: Contratos como bundles independientes, versionados y nunca empaquetados en la aplicación

- **Estado:** Aceptado
- **Fecha:** 2026-10-25

## Contexto

La aplicación Jakarta EE y los plugins OSGi tienen que compartir **exactamente las mismas clases** del contrato. Si la WAR llevara su propia copia del contrato en `WEB-INF/lib`, existirían dos clases `GreetingService` con el mismo nombre en class loaders distintos: el servicio del plugin no sería asignable al tipo que espera la aplicación (`ClassCastException`) o, directamente, no se encontraría.

En GlassFish, las aplicaciones ven los paquetes exportados por los bundles a través del *API exporter* (`DynamicImport-Package: *`). La PoC confirmó que funciona. Pero un import dinámico queda enlazado a la revisión del bundle que exportaba el paquete en el momento del enlace.

## Decisión

1. Cada contrato es un **bundle independiente** que solo contiene interfaces y tipos de valor (por ejemplo `record`), sin dependencias de OSGi, CDI ni GlassFish.
2. Los paquetes del contrato se exportan con **versión semántica** explícita (`package-info.java` con `@Export` y `@Version`). Las interfaces implementadas por los plugins se anotan `@ProviderType`.
3. Las aplicaciones y los plugins declaran el contrato con `scope provided`. **Nunca se empaqueta dentro de la aplicación.**
4. El contrato se despliega **antes** que las aplicaciones que lo usan.
5. **Las implementaciones son dinámicas; los contratos no lo son en caliente.** Cambiar un contrato exige redesplegar, por este orden: el contrato, los plugins que lo implementan y las aplicaciones que lo consumen.

## Alternativas consideradas

| Alternativa | A favor | En contra |
|---|---|---|
| Contrato dentro de la WAR | Despliegue de un solo artefacto | Clases duplicadas y servicios incompatibles; el puente lo detecta y falla con un mensaje explícito |
| Contrato en `domain/lib` (class loader común) | Visible para todos | Fuera del mundo OSGi: los plugins no lo resolverían como paquete versionado |
| **Contrato como bundle** | Una sola copia de las clases, versionado verificable con bnd | Orden de despliegue obligatorio y redespliegue de dependientes al cambiar el contrato |

## Consecuencias

- **Positivas:** las implementaciones se sustituyen libremente; las incompatibilidades se detectan al resolver los bundles, no en producción.
- **Negativas:** la evolución de contratos requiere disciplina. Se prefiere añadir interfaces nuevas a modificar las existentes.
- **Comportamiento verificado en GlassFish 8.0.5:** al redesplegar un contrato, GlassFish deja los plugins dependientes en estado `Installed`, y la aplicación sigue enlazada a la revisión anterior. El puente lo detecta y responde *"Contract bundle … was uninstalled or replaced. Redeploy the application…"* en lugar de servir un servicio obsoleto (ver [findings.md](../findings.md), H-3).

## Verificación

- La WAR generada no contiene el JAR del contrato: `unzip -l atlas-hybrid-demo.war | grep greeting-api` no devuelve nada.
- Fase 1 de la hoja de ruta: `bnd-baseline-maven-plugin` hará fallar el build si cambia la API sin subir la versión del paquete según semver.

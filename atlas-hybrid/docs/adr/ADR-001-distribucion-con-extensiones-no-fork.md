# ADR-001: Extensiones sobre GlassFish sin modificar, no un fork del core

- **Estado:** Aceptado
- **Fecha:** 2026-10-25

## Contexto

La organización quiere un GlassFish "propio" con características diferenciales, sin dejar de recibir las mejoras y correcciones de seguridad de la línea principal (Eclipse GlassFish).

Cada cambio en el core de un fork es deuda que se paga en cada sincronización con upstream. Payara nació como fork de GlassFish y hoy es, en la práctica, otro producto. Además, el valor diferencial identificado no está en el core del servidor, sino en una **línea de integración de aplicaciones híbridas Jakarta EE + OSGi**.

GlassFish ya ofrece los puntos de extensión necesarios: bundles OSGi (`asadmin deploy --type=osgi`), extensiones CDI portables, servicios HK2 y opciones de configuración por dominio.

## Decisión

1. **No se modifica ningún fichero del core de GlassFish.** Todo el valor propio se entrega como artefactos desplegables (bundles, JARs de aplicación) y como configuración de dominio.
2. El código de Atlas Hybrid vive en `atlas-hybrid/`, un **proyecto Maven independiente que no forma parte del reactor** de GlassFish. Las fusiones desde upstream nunca tocan esos ficheros, así que no generan conflictos.
3. La rama `main` del repositorio se mantiene como espejo de upstream. El trabajo propio va en ramas que fusionan `main` periódicamente.
4. Si alguna vez hace falta un cambio en el core, primero se propone upstream. Solo si es imprescindible y no se acepta, se mantiene como parche pequeño, aislado, etiquetado `[ATLAS-PATCH]` y registrado en un inventario.
5. Los defectos de GlassFish que encuentre Atlas se corrigen **en upstream**, no en local (ver [findings.md](../findings.md)).

## Alternativas consideradas

| Alternativa | A favor | En contra |
|---|---|---|
| Fork completo del core | Control total | Coste de sincronización creciente, divergencia, obligaciones de marca ("Eclipse GlassFish") y licencia |
| Repositorio aparte (p. ej. `atlas`) | Separación máxima | Se pierde la cercanía con el código de GlassFish, útil para aprender y diagnosticar |
| **Proyecto independiente dentro del repo, fuera del reactor** | Cero conflictos con upstream, código de GlassFish a mano, migración trivial a otro repo | Hay que recordar que no se compila con el build de GlassFish |

## Consecuencias

- **Positivas:** actualizar GlassFish se reduce a desplegar la nueva versión y volver a ejecutar `configure-domain.sh`; la PoC funciona sobre la distribución oficial 8.0.5 tal cual.
- **Negativas:** algunas carencias del servidor se resuelven con configuración y no con código (ver ADR-005); dependemos de los tiempos de upstream para las correcciones.
- **Obligatorio:** toda funcionalidad nueva debe justificar por qué no puede ser una extensión.

## Verificación

- `git diff origin/main -- . ':!atlas-hybrid'` debe estar vacío en las ramas de Atlas.
- La demo se ejecuta sobre la distribución oficial descargada de Maven Central, sin recompilar GlassFish.

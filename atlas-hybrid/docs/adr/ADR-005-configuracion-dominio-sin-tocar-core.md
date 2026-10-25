# ADR-005: Perfiles `JavaSE/compact` mediante una opción JVM del dominio

- **Estado:** Aceptado (provisional, hasta la corrección upstream)
- **Fecha:** 2026-10-25

## Contexto

Todos los bundles publicados por la OSGi Alliance que necesita el runtime DS (`org.osgi.util.function`, `org.osgi.util.promise`, `org.osgi.service.component`) declaran:

```
Require-Capability: osgi.ee;filter:="(&(osgi.ee=JavaSE/compact1)(version=1.8))"
```

El `osgi.properties` de GlassFish 8.0.5 (`eecap-21`) declara `OSGi/Minimum`, `JavaSE` 1.0–21 y `UNKNOWN`, pero **no los perfiles `JavaSE/compact1..3`**, que el Felix estándar sí declara. El resultado es que esos bundles no resuelven:

```
missing requirement [...] osgi.ee; (&(osgi.ee=JavaSE/compact1)(version=1.8))
```

Se probaron tres vías:

| Vía | Resultado |
|---|---|
| `config/osgi.properties` a nivel de dominio (GlassFish lo prefiere al de la instalación) | **GlassFish 8.0.5 no arranca:** al registrar qué fichero usa, el logger de bootstrap busca `LogMessages.properties`, que no está en `glassfish.jar` (`MissingResourceException`). Ver findings H-2. |
| Fragmento de extensión del framework con `Provide-Capability: osgi.ee` | La especificación OSGi lo prohíbe y bnd lo rechaza. |
| **Propiedad de sistema que sobrescribe `org.osgi.framework.system.capabilities`** | Funciona. GlassFish permite sobrescribir con propiedades de sistema cualquier clave de `osgi.properties` (`StartupContextCfgFactory.overrideBySystemProps`). |

## Decisión

`runtime/configure-domain.sh` añade al dominio la opción JVM `-Dorg.osgi.framework.system.capabilities=...`, que contiene:

- el valor que GlassFish calcula para la versión de Java en uso, **leído del `osgi.properties` de la instalación**, y
- `osgi.ee=JavaSE/compact1..3` con versión 1.8.

Como `asadmin` elimina las comillas dobles de las opciones JVM, el valor se escribe sin comillas, con una cláusula por versión (`osgi.ee;osgi.ee=JavaSE;version:Version=21`), lo cual es equivalente.

La instalación de GlassFish no se modifica (ADR-001).

## Consecuencias

- **Positivas:** se aplica con `asadmin`, es reversible (`delete-jvm-options`) e idempotente (el script sustituye el valor previo).
- **Negativas:** el valor queda fijado a la versión de Java y de GlassFish en el momento de ejecutar el script. **Hay que volver a ejecutar `configure-domain.sh` tras actualizar GlassFish o el JDK.** Está en el runbook de [operations.md](../operations.md).
- **Salida:** cuando upstream incluya los perfiles `compact` en `eecap-*` (propuesta P-1 de findings), este ADR pasa a *Obsoleto* y basta con `asadmin delete-jvm-options`.

## Verificación

- `asadmin list-jvm-options | grep system.capabilities` muestra la opción.
- `asadmin osgi lb` muestra `Apache Felix Declarative Services` en estado `Active`.

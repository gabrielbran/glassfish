# ADR-008: Secretos de aplicación sobre los password alias de GlassFish, mediante un adaptador de plataforma

- **Estado:** Aceptado
- **Fecha:** 2026-10-26

## Contexto

Las aplicaciones necesitan guardar y recuperar credenciales (claves de API, contraseñas de sistemas externos) sin dejarlas en código, ficheros de propiedades ni base de datos en claro. GlassFish ya ofrece un almacén cifrado con la *master password* del dominio: los **password alias** (`domain-passwords.p12`), que se gestionan con `asadmin create/update/delete/list-password-alias(es)` y se referencian en la configuración como `${ALIAS=nombre}`.

GlassFish 8 no ofrece una API **pública** para que las aplicaciones usen ese almacén. Internamente existen:

| Pieza | Tipo | Uso |
|---|---|---|
| `DomainScopedPasswordAliasStore` (`com.sun.enterprise.security.store`, `common-util`) | Servicio HK2, `@PerLookup` | Lectura y escritura directa del keystore |
| `create-password-alias` | Comando admin, `@ExecuteOn(ALL)`, `@AccessRequired` | Alta, replicada a las instancias |
| `update-password-alias` | Comando admin, `@ExecuteOn(DAS)` | Modificación (solo en el DAS, ver findings H-7) |
| `delete-password-alias` | Comando admin, `@ExecuteOn(ALL)` | Baja, replicada a las instancias |
| `CommandRunner` + `InternalSystemAdministrator` | Servicios HK2 | Ejecutar comandos admin desde código, como hace el propio GlassFish embebido |

El ADR-002 prohíbe usar HK2 en plugins de negocio. Pero integrar una capacidad **del servidor** obliga, por definición, a tocar sus internals.

## Decisión

1. **Contrato neutral** `atlas-hybrid-secrets-api` → `SecretStore` (`read`, `exists`, `store`, `delete`, `names`). Sin tipos de GlassFish. Los valores viajan como `char[]` para que se puedan borrar tras su uso.
2. **Adaptador de plataforma** `atlas-hybrid-plugin-secrets-glassfish`: un plugin DS que implementa el contrato sobre los password alias.
   - **Lecturas:** directas al `DomainScopedPasswordAliasStore`. Al ser `@PerLookup`, ven los cambios hechos con `asadmin`.
   - **Escrituras:** a través de los **comandos admin oficiales**, ejecutados con `CommandRunner` y la identidad interna del sistema. Así se respetan su validación, sus bloqueos y la replicación a las instancias, en lugar de escribir el keystore por debajo.
3. **Confinamiento por espacio de nombres:** el nombre lógico `crm.api-key` se guarda como alias `atlas.crm.api-key`. El prefijo es configurable (Config Admin, PID `org.atlas.hybrid.secrets.glassfish`, propiedad `atlas.secrets.prefix`) y **nunca puede estar vacío**. Los alias fuera del prefijo (admin, keystores, pools JDBC…) no se pueden leer, listar, sobrescribir ni borrar desde las aplicaciones.
4. **Nombres validados** con `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`: impide las secuencias `..`, `${ALIAS=…}`, espacios y nombres ocultos.
5. **Los valores nunca salen en mensajes de error ni en logs.** La demo solo expone una huella SHA-256 truncada.
6. **"Adaptador de plataforma"** pasa a ser una categoría explícita: un plugin cuyo único fin es exponer una capacidad del servidor a través de un contrato neutral **puede** usar internals de GlassFish. Debe declararlos con un rango de versión estricto (`[8.0,9)`, que genera bnd) y es el único sitio donde se tocan. Los plugins de negocio siguen sin usar HK2 (ADR-002).
7. **Autorización:** el EJB que expone la capacidad debe protegerse con `@RolesAllowed`. Quien pueda llamarlo gestiona todos los secretos del espacio de nombres. La demo lo deja abierto solo para ser autocontenida.

## Alternativas consideradas

| Alternativa | A favor | En contra |
|---|---|---|
| La aplicación usa directamente `Globals.getDefaultHabitat()` | Sin plugin | Acopla cada aplicación a los internals de GlassFish; sin espacio de nombres; cada app reimplementa la seguridad |
| Escribir directamente en `DomainScopedPasswordAliasStore.put` | Más simple | Se salta los comandos: sin replicación al clúster ni validación |
| Solo `${ALIAS=…}` en la configuración del dominio | Estándar y sin código | Solo lectura, y solo donde la configuración lo admite (p. ej. contraseñas de pools JDBC) |
| Almacén externo (Vault, KMS) | Rotación y auditoría avanzadas | Infraestructura nueva. **El contrato lo permite en el futuro:** basta otro plugin `SecretStore` con mayor `service.ranking` |
| **Contrato + adaptador sobre los password alias** | Reutiliza el almacén cifrado del dominio, interoperable con `asadmin` y `${ALIAS=…}`, internals aislados en un único bundle | El adaptador debe revisarse en cada versión mayor de GlassFish |

## Consecuencias

- Una aplicación EJB híbrida usa secretos con `@Inject @OsgiService SecretStore`, sin ninguna dependencia de GlassFish.
- Los secretos creados por las aplicaciones son alias normales: los ve `asadmin list-password-aliases` y se pueden usar como `${ALIAS=atlas.<nombre>}` en la configuración del dominio, por ejemplo en la contraseña de un pool JDBC.
- **Limitación:** el comando admin recibe la contraseña como `String`, una copia inevitable de vida corta.
- **Pendiente:** validar el comportamiento en clúster, porque `update-password-alias` solo se ejecuta en el DAS (findings H-7).

## Verificación

- 7 tests unitarios del adaptador con un backend en memoria: espacio de nombres, validación de nombres, copias, mensajes sin valores, idempotencia.
- `runtime/demo.sh`, paso 6, contra GlassFish 8.0.5 (10 verificaciones): alta, lectura por huella, visibilidad en `asadmin`, actualización, lectura de alias creados con `asadmin`, aislamiento de alias del servidor, rechazo de nombres inválidos y borrado.

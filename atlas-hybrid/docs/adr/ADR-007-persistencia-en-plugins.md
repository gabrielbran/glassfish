# ADR-007: Persistencia en plugins: `DataSource` del servidor por JNDI dentro de la transacción JTA del llamante

- **Estado:** Aceptado (JPA dentro de un plugin: pendiente de validar)
- **Fecha:** 2026-10-26

## Contexto

Algunos plugins de negocio necesitan leer o escribir en base de datos. En una aplicación Jakarta EE eso se resuelve con `@PersistenceContext EntityManager`. Pero un `@Component` de Declarative Services **no es un componente Jakarta EE**: SCR no procesa `@PersistenceContext`, `@PersistenceUnit`, `@Resource` ni `@Transactional`.

Requisitos:
- Los datos que escribe un plugin deben confirmarse o revertirse **junto con** la operación de la aplicación que lo invoca.
- Hay que reutilizar los pools, la seguridad y la monitorización del servidor, sin conexiones propias.
- Los contratos no deben depender de JPA (ADR-003).

El experimento H-6 ([findings.md](../findings.md)) demostró en GlassFish 8.0.5 que:
- un plugin puede obtener por JNDI los `DataSource` del servidor;
- como el puente invoca al plugin **en el mismo hilo** que la petición, las conexiones obtenidas participan en la transacción JTA activa del llamante. El rollback de la aplicación revierte las escrituras del plugin.

## Decisión

1. **Las aplicaciones gestionan las transacciones** (`@Transactional`, EJB). Los plugins nunca inician ni confirman transacciones globales.
2. Un plugin que necesite persistencia obtiene un **recurso JDBC del servidor por JNDI** (por ejemplo `jdbc/miPlugin`), **en el momento de la llamada**. Puede cachear el `DataSource` tras el primer lookup correcto, pero nunca hace el lookup en `@Activate`, porque los pools se inicializan de forma perezosa.
3. El nombre JNDI se configura como propiedad del componente (Config Admin, fase 2), no se escribe en el código.
4. Las conexiones se cierran siempre (`try-with-resources`). El pool y la JTA del servidor se encargan del resto.
5. **Entidades JPA:**
   - Si el modelo es de la aplicación, las entidades y el `EntityManager` siguen en la aplicación, y el plugin recibe o devuelve DTOs del contrato.
   - Si el plugin tiene un modelo propio, JDBC o un mapeador ligero embebido en el plugin (ver la guía de plugins).
   - **JPA dentro del plugin** (EclipseLink con `createContainerEntityManagerFactory` y un `jtaDataSource`) queda **pendiente de validar** antes de recomendarlo.

## Alternativas consideradas

| Alternativa | A favor | En contra |
|---|---|---|
| `@PersistenceContext` en el `@Component` | Familiar | No existe: SCR no es un contenedor Jakarta EE |
| Pasar `EntityManager` como parámetro del contrato | Reutiliza la unidad de persistencia de la app | Acopla el contrato a JPA y a las entidades de la aplicación |
| OSGi JPA Service (cap. 127) / FighterFish `osgi-jpa` | Estándar OSGi | No disponible en GlassFish 8 (FighterFish sigue en `javax`) |
| Conexiones propias del plugin (pool embebido) | Independencia | Fuera de la JTA y de los pools, la monitorización y la seguridad del servidor |
| **`DataSource` del servidor por JNDI en la JTA del llamante** | Atómico con la operación de la app, sin infraestructura nueva, comprobado | Depende del JNDI de GlassFish y exige disciplina: lookup perezoso y nada de transacciones propias |

## Consecuencias

- Una operación de negocio que cruza aplicación y plugin es **atómica**.
- Los plugins quedan ligados a recursos JNDI del servidor, que se documentan como requisito de despliegue de cada plugin.
- Llamar al plugin fuera de una transacción (o desde un hilo propio del plugin) hace que sus conexiones trabajen en autocommit. Es válido para lecturas y debe evitarse para escrituras.

## Verificación

- Experimento H-6: rollback de la aplicación ⇒ el INSERT del plugin no persiste.
- Pendiente: test de integración permanente (Arquillian o `demo.sh`) con un plugin de ejemplo persistente.

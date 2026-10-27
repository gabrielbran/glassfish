# ADR-010: Orquestación en memoria: el orquestador coordina; la transacción es del llamante; saga para lo no transaccional

- **Estado:** Aceptado (flujos cortos). Los flujos duraderos tendrán su propio ADR
- **Fecha:** 2026-10-27

## Contexto

En Atlas, las actividades (llamadas REST, operaciones de base de datos…) son plugins con una única responsabilidad. Un **plugin orquestador** las ejecuta en secuencia, en paralelo esperando el resultado, o en modo disparar y olvidar. Según las respuestas puede **provocar un rollback**.

Hechos técnicos que condicionan el diseño:
- Una llamada HTTP **no participa** en una transacción JTA: un rollback no la deshace.
- Una transacción JTA está **ligada a un hilo**: lo que corre en otro hilo queda fuera de ella.
- Un plugin obtiene recursos JDBC del servidor y estos se unen a la transacción del llamante (ADR-007).

Hay flujos cortos y largos; esta decisión cubre **los cortos, en memoria**.

## Decisión

1. **El orquestador es un plugin** (`orchestrator@1.0.0`) que implementa `FlowEngine` y también `Plugin` (operación `run`), de modo que un flujo puede invocar a otro como actividad.
2. **Las actividades no conocen la coordinación.** RestClient y Database no tienen modos de envío ni estado de transacción.
3. **La transacción la abre la aplicación** (EJB o `@Transactional`). El orquestador solo se une a ella mediante el estándar `java:comp/TransactionSynchronizationRegistry`: marca rollback y registra acciones para después del commit o del rollback. Así no depende de internals de GlassFish.
4. **Deshacer depende del tipo de actividad:**
   - transaccional (Database) → **rollback JTA**;
   - no transaccional (RestClient) → **compensación** declarada en la metadata, ejecutada **en orden inverso**;
   - asíncrona pendiente → **se descarta**.
5. **Paralelismo con espera** en el `DefaultManagedExecutorService` de la aplicación, con un timeout por paso. Las ramas van fuera de la transacción, por lo que **los plugins transaccionales no pueden ir en paralelo ni en modo asíncrono**. Esto se valida en ejecución, con un mensaje explícito.
6. **Disparar y olvidar** se envía **tras el commit** cuando hay transacción, y de inmediato cuando no la hay.
7. **Si la transacción del llamante hace rollback después de un flujo completado**, el orquestador también compensa (saga completa).
8. **Versión exacta** en cada actividad (ADR-009). No se cambia de versión en silencio.
9. **Errores como datos:** `run` devuelve un `FlowResult` (`COMPLETED`, `ABORTED` o `FAILED`) y no lanza excepciones por fallos de las actividades. Un subflujo que no completa sí lanza `PluginException` para que el flujo padre compense.

## Alternativas consideradas

| Alternativa | En contra |
|---|---|
| Modos de envío dentro del RestClient (propuesta anterior) | Mezcla responsabilidades: el conector tendría estado de transacción |
| `@TransactionScoped` en los plugins | Instancias por transacción: un pool HTTP nuevo cada vez. El estado de la ejecución pertenece al orquestador |
| El orquestador abre sus propias transacciones (`TransactionManager`) | Requiere el nombre JNDI propio de GlassFish (`java:appserver/…`) y duplica lo que el contenedor ya hace bien. Si algún flujo lo necesita, irá en un adaptador de plataforma (ADR-008) |
| Propagar la transacción a las ramas paralelas | Es opcional en Jakarta Concurrency y no está validado en GlassFish; no se asume |
| Motor BPMN (Flowable…) para los flujos cortos | Desproporcionado para la orquestación en memoria; se reevaluará para los duraderos |

## Consecuencias

- Una operación que cruza base de datos y servicios REST es **atómica en la base de datos** y **consistente de forma eventual** fuera de ella (compensaciones).
- Las compensaciones deben ser **idempotentes** (puede que el recurso ya no exista) y las escrituras REST deberían llevar `Idempotency-Key` (`${execution.id}-<paso>`).
- **Límites aceptados para esta fase:** sin persistencia ni reintentos (ver orchestrator.md, sección 8).

## Verificación

- 12 tests unitarios del motor (`FlowExecutionTest`): secuencia, aborto con compensación en orden inverso, error con código, versión exacta, reglas de transaccionalidad, paralelo concurrente, timeout con compensación de ramas completadas, disparar y olvidar tras el commit y descartado en rollback, compensación tras el rollback del llamante, expresión no resoluble, fallos de compensación y validación de la metadata.
- `runtime/flows-demo.sh` contra GlassFish 8.0.5: 20 verificaciones (escenarios A–G).

# Orquestador en memoria: referencia

- **Estado:** validado sobre GlassFish 8.0.5 (`runtime/flows-demo.sh`, 20 verificaciones; 12 tests unitarios del motor)
- **Plugin:** `orchestrator@1.0.0` (bundle `atlas.plugin.orchestrator`), contrato `org.atlas.flow.api.FlowEngine`
- **Alcance:** flujos **cortos**. La ejecución vive mientras dura la llamada y **no sobrevive a un reinicio**. Los flujos duraderos (Oracle, clúster) son la fase siguiente (ver la sección 8).

## 1. Piezas

```
 Aplicación Jakarta EE                         Plugins OSGi (bundles, versión exacta)
┌──────────────────────────────┐   run()     ┌──────────────────────────────────────────────┐
│ @Stateless FlowService       │────────────►│ orchestrator@1.0.0  (FlowEngine + Plugin)    │
│  @Inject @AtlasPlugin(       │             │   lee la metadata, ejecuta las actividades,  │
│   name="orchestrator",       │             │   aplica reglas, rollback y compensaciones   │
│   version="1.0.0")           │             └──────┬───────────────┬───────────────┬───────┘
│  FlowEngine engine;          │                    │ execute()     │               │
│ (abre la transacción JTA)    │             database@1.0.0  rest-client@1.0.0  rest-client@2.0.0
└──────────────────────────────┘             (JDBC + JTA)    (OkHttp 4.12.0)    (OkHttp 5.3.0)
```

- Todos los plugins implementan `org.atlas.plugin.api.Plugin`: `PluginResult execute(PluginRequest)`, con parámetros y resultados de tipo JSON.
- **Responsabilidad única:** RestClient solo conecta y devuelve la respuesta; Database solo ejecuta SQL; **el orquestador** coordina, decide el rollback y compensa.

## 2. Formato de la metadata

```json
{
  "name": "alta-cliente",
  "vars": { "mock": "http://servicios.interno/api", "ds": "jdbc/clientes" },
  "steps": [
    { "id": "guardar", "plugin": "database@1.0.0", "operation": "update",
      "params": { "dataSource": "${vars.ds}",
                  "sql": "INSERT INTO CLIENTE (DNI, NOMBRE) VALUES (?, ?)",
                  "args": ["${input.dni}", "${input.nombre}"] } },

    { "id": "consultas", "timeoutMs": 3000, "parallel": [
        { "id": "buro",   "plugin": "rest-client@1.0.0", "operation": "call",
          "params": { "url": "${vars.mock}/buro/${input.dni}" } },
        { "id": "listas", "plugin": "rest-client@2.0.0", "operation": "call",
          "params": { "url": "${vars.mock}/listas-negras/${input.dni}" } } ],
      "abortIf": [ { "path": "steps.listas.body.encontrado", "equals": true,
                     "message": "Cliente en lista negra" } ] },

    { "id": "crm", "plugin": "rest-client@2.0.0", "operation": "call",
      "params": { "method": "POST", "url": "${vars.mock}/crm/clientes",
                  "headers": { "Idempotency-Key": "${execution.id}-crm" },
                  "body": { "dni": "${input.dni}", "score": "${steps.buro.body.score}" } },
      "compensation": { "plugin": "rest-client@2.0.0", "operation": "call",
                        "params": { "method": "DELETE",
                                    "url": "${vars.mock}/crm/clientes/${steps.crm.body.id}" } } },

    { "id": "scoring", "plugin": "rest-client@1.0.0", "operation": "call",
      "params": { "url": "${vars.mock}/scoring/${input.dni}" },
      "abortIf": [ { "path": "steps.scoring.body.resultado", "equals": "RECHAZADO",
                     "message": "Scoring rechazado" } ] },

    { "id": "notificar", "plugin": "rest-client@2.0.0", "operation": "call", "async": true,
      "params": { "method": "POST", "url": "${vars.mock}/notificaciones",
                  "body": { "evento": "CLIENTE_ALTA", "crmId": "${steps.crm.body.id}" } } }
  ]
}
```

| Elemento | Significado |
|---|---|
| `plugin` | `"nombre@major.minor.micro"` o `{"name": …, "version": …}`. **Versión exacta, siempre**: si esa versión no está instalada, el paso falla; nunca se usa otra en su lugar |
| `operation`, `params` | Operación y parámetros del plugin. Los parámetros admiten expresiones |
| `abortIf` | Reglas sobre el resultado: `equals`, `notEquals` o `greaterOrEqual`. La primera que se cumple **aborta** el flujo (`ABORTED`) |
| `compensation` | Actividad que deshace el paso si el flujo aborta o falla después. Solo para plugins **no transaccionales** |
| `parallel` + `timeoutMs` | Ramas concurrentes; el flujo **espera a todas** (por defecto 30 s). `abortIf` del paso paralelo se evalúa al terminar todas |
| `async: true` | **Disparar y olvidar**: no se espera, su resultado no es visible y no admite `abortIf` ni `compensation` |

**Expresiones `${…}`:** `input.<ruta>`, `vars.<nombre>`, `steps.<id>.<ruta>` (por ejemplo `steps.consulta.rows.0.NOMBRE`) y `execution.id` / `execution.flow`. Si la expresión ocupa todo el valor, conserva su tipo (número, objeto…); dentro de un texto, se sustituye por su representación textual. **Una expresión que no se puede resolver hace fallar el paso**: nunca se envía una petición incompleta.

La definición se valida entera al registrarla (`FlowEngine.define`): ids únicos, versiones exactas, operadores de reglas válidos, y que los pasos asíncronos no tengan compensación.

## 3. Semántica de ejecución

| Situación | Qué hace el orquestador |
|---|---|
| Paso **transaccional** (Database) | Se ejecuta en el hilo de quien llama, **dentro de su transacción JTA**. Sin transacción activa, el paso falla |
| Paso **no transaccional** completado (RestClient) | Si tiene `compensation`, se apila para deshacerlo en caso de fallo |
| Regla `abortIf` cumplida | `ABORTED` |
| Error del plugin (`PluginException`: `TIMEOUT`, `HTTP_ERROR`, `CONNECTION`, `SQL`…), plugin no instalado, expresión no resoluble, timeout del paralelo | `FAILED` |
| `ABORTED` o `FAILED` | 1. **marca la transacción para rollback**, y la base de datos se deshace; 2. ejecuta las **compensaciones en orden inverso**, y si una falla, sigue con las demás y la informa en `compensationFailures`; 3. **descarta** los pasos asíncronos pendientes |
| Paso en **paralelo** | Las ramas se ejecutan en el `DefaultManagedExecutorService` de la aplicación, **fuera de la transacción**: no admiten plugins transaccionales. Si una rama falla o vence el plazo, se cancelan las demás y se compensan las ya completadas |
| Paso **asíncrono** con transacción | Se envía **después del commit**. Si hay rollback, se descarta |
| Paso **asíncrono** sin transacción | Se envía de inmediato |
| El flujo termina, pero **la transacción de quien llama hace rollback después** | Al detectar el rollback, el orquestador **compensa** el flujo completado y descarta los pasos asíncronos |

El resultado (`FlowResult`) incluye `status`, `failedStep`, `message`, `outputs` (resultado de cada paso), `compensated`, `compensationFailures`, `dispatched` y `durationMillis`. El motor **nunca lanza excepciones por fallos de las actividades**: los informa en el resultado.

## 4. Quién abre la transacción

La aplicación: un EJB `@Stateless` (o un método `@Transactional`) llama a `FlowEngine.run`. El orquestador solo se une a esa transacción (vía el estándar `java:comp/TransactionSynchronizationRegistry`), la marca para rollback y registra lo que debe pasar tras el commit o el rollback. **Nunca abre ni confirma transacciones.** Ver ADR-010.

## 5. RestClient (`rest-client`)

Operación `call`. Parámetros:
- `url` (obligatorio), `method` (`GET` por defecto), `headers`, `query`;
- `body`: un objeto o lista se envía como JSON, cualquier otro valor como texto;
- `timeoutMs`: plazo total de la llamada, por defecto 10 000;
- `errorStatus`: códigos HTTP iguales o superiores provocan `HTTP_ERROR`. Por defecto 500, de modo que los 4xx se devuelven para que las reglas decidan.

Resultado: `status`, `headers`, `body` (parseado si la respuesta es JSON), `durationMillis` y `client` (versión del plugin y de OkHttp).

## 6. Database (`database`)

Operaciones `update` (devuelve `updated`) y `query` (devuelve `rows` y `count`; `maxRows` por defecto 100). Parámetros: `sql` con `?`, `args`, y `dataSource` (nombre JNDI, por defecto `jdbc/__default`). Los datos van **siempre** como parámetros enlazados, nunca dentro del SQL. Es transaccional (`atlas.plugin.transactional=true`).

## 7. Escenarios verificados (`runtime/flows-demo.sh`)

| Escenario | Resultado comprobado |
|---|---|
| A. Alta correcta | `COMPLETED`; fila confirmada; cliente en el CRM; buró por RestClient **1.0.0 / OkHttp 4.12.0** y listas por **2.0.0 / OkHttp 5.3.0** en paralelo; notificación entregada tras el commit |
| B. Scoring `RECHAZADO` | `ABORTED` en `scoring`; INSERT revertido; cliente del CRM **compensado** (DELETE); notificación descartada |
| C. Buró tarda 5 s, plazo 3 s | `FAILED` en `buro` a los ~3 s; INSERT revertido; el CRM nunca se llama |
| D. Cliente en lista negra | `ABORTED` en el paso paralelo `consultas`; INSERT revertido |
| E. El flujo termina, pero el EJB falla después | Transacción revertida; el CRM se **compensa tras el rollback**; notificación descartada |
| F. Catálogo | `rest-client` 1.0.0 y 2.0.0, `database` 1.0.0, `orchestrator` 1.0.0, todos activos a la vez |
| G. Versión exacta en caliente | Sin `rest-client@1.0.0`, el flujo falla (no usa la 2.0.0 en su lugar); al reinstalarla funciona sin redesplegar la aplicación |

## 8. Límites de esta versión (en memoria)

- **Nada sobrevive a un reinicio:** ni las ejecuciones en curso, ni las definiciones registradas (las aplicaciones las vuelven a registrar al detectar que faltan), ni los pasos asíncronos pendientes.
- **Disparar y olvidar sin reintentos:** si el envío falla, solo queda en el log.
- **Las compensaciones fallidas** solo se informan (`compensationFailures` y log `SEVERE`); no se reintentan.
- **Las ramas paralelas** van fuera de la transacción: solo actividades no transaccionales.
- Una regla compara un único valor; no hay expresiones lógicas compuestas.
- **La transformación de respuestas** está aparcada por decisión de diseño, y se retomará como plugin propio.

La siguiente fase, flujos duraderos sobre **Oracle** y en **clúster**, resolverá los tres primeros límites con una bitácora persistente, una cola duradera con reintentos y la recuperación tras un reinicio.

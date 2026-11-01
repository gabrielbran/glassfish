# ADR-011: Ciclo de vida de tráfico por instancia: validar antes de recibir carga y drenar antes de parar

- **Estado:** Aceptado
- **Fecha:** 2026-11-01

## Contexto

Producción: clúster de GlassFish 8 detrás de **HAProxy**, base de datos **Oracle**. Requisitos del responsable:
1. Una instancia no debe recibir carga transaccional hasta que la aplicación esté realmente lista, validada con **consultas de solo lectura** a la base de datos.
2. Hay que poder **drenar** una instancia (HTTP, JDBC, EJB) antes de pararla o actualizarla.

GlassFish 8.0.5 no ofrece ninguna de las dos cosas, y la PoC midió además varios defectos y riesgos (findings H-9 a H-13):
- `stop-instance` interrumpe las transacciones en curso.
- `/health/ready` responde UP antes de que carguen las aplicaciones.
- MicroProfile Health no funciona en las instancias de un clúster.
- Los sondeos de salud compiten con el negocio por un pool de 5 hilos.

## Decisión

1. **Una extensión del servidor** `atlas-server-traffic`: un módulo HK2, que es justo para lo que el ADR-002 reserva HK2. Se instala como **módulo adicional** en `glassfish/modules` de cada nodo. **No se modifica ningún fichero de GlassFish** (ADR-001).
2. **Máquina de estados por instancia:** STARTING → VALIDATING → READY → DRAINING → DRAINED, con `atlas-undrain` de vuelta a VALIDATING (nunca directo a READY).
3. **Preparación vía el estándar MicroProfile Health** (`/health/ready`), que es lo que sondea HAProxy:
   - el check `atlas-traffic` se registra **en el primer *run level*** (antes de cargar aplicaciones) y responde DOWN hasta READY;
   - si GlassFish no registró el `HealthReporter` (clúster, H-9), el módulo lo registra igual que lo haría GlassFish;
   - los checks `@Readiness` de las aplicaciones siguen participando con normalidad.
4. **Validación:** aplicaciones cargadas + checks de las aplicaciones en UP + consultas de **solo lectura** configurables por clúster, N veces seguidas. El solo lectura se garantiza con filtro de sentencia, conexión `readOnly` y rollback.
5. **Drenaje:**
   - readiness DOWN;
   - gracia con `Connection: close`;
   - 503 para lo nuevo;
   - espera de **HTTP en curso** (filtro de servidor declarado una vez en `default-web.xml`), **transacciones JTA activas** (cubre EJB, timers y MDB) y **conexiones JDBC en uso**;
   - vaciado de pools y DRAINED.
   
   Se expone con los comandos `atlas-drain`, `atlas-undrain` y `atlas-traffic-status`, que se replican a las instancias del clúster.
6. **Reglas de despliegue obligatorias:** listener de salud dedicado con pool propio; HAProxy sin reintentos de capa 7 para métodos no idempotentes; validación real de conexiones en los pools (`OracleConnectionValidation`); `fall × inter` del balanceador menor que la gracia.
7. **Reinicio rotatorio** (`rolling-restart.sh`): drenar → parar → actualizar → arrancar → esperar READY, instancia por instancia.

## Alternativas consideradas

| Alternativa | En contra |
|---|---|
| Confiar en `stop-instance` | Corta transacciones en curso (medido: 500 y conexiones cortadas) |
| Solo checks `@Readiness` en cada aplicación | No cubre el arranque (UP antes de cargar), ni el drenaje, ni el estado de la instancia; cada aplicación lo reimplementaría |
| Deshabilitar la aplicación (`disable`) para drenar | Descarga la aplicación: corta lo que está en curso |
| `agent-check` de HAProxy con un agente externo | Infraestructura adicional que no conoce el trabajo en curso dentro de GlassFish |
| Filtro en cada aplicación | Obliga a cambiar todas las aplicaciones; el filtro de servidor en `default-web.xml` no requiere ningún cambio en ellas |
| Modificar el core de GlassFish | Contrario al ADR-001; los defectos de GlassFish encontrados se proponen upstream |

## Consecuencias

- **Medido:** reinicio rotatorio del clúster bajo carga, **0 fallos y 0 reenvíos**; sin drenaje, POST interrumpidos (500 y conexiones cortadas).
- El módulo usa APIs internas (`HealthReporter`, `PoolManager`, `JavaEETransactionManager`, `ApplicationRegistry`), con importaciones fijadas a `[8.0,9)`. Debe revalidarse con cada versión de GlassFish (la demo es la prueba de regresión).
- Hay que distribuirlo a cada nodo del clúster.
- **Límites aceptados:** los timers EJB no se pausan (GlassFish no soporta suspender el contenedor EJB), los servlets asíncronos y los EJB remotos sin transacción no se cuentan, y JMS queda fuera del alcance. Ver traffic.md, sección 9.
- **Contribución upstream candidata:** el drenaje ordenado y la corrección de MicroProfile Health en las instancias (P-4).

## Verificación

- 7 tests unitarios de la máquina de estados y del filtro SQL de solo lectura.
- `runtime/traffic/traffic-demo.sh` sobre un clúster de dos instancias y HAProxy, con 23 verificaciones:
  - las dos instancias READY;
  - drenaje con HTTP, JTA y JDBC en curso sin cortes;
  - 503 tras la gracia y HAProxy sacando la instancia;
  - timeout de drenaje informado;
  - instancia fuera mientras la BD está caída y dentro al volver;
  - check de la aplicación;
  - reinicio rotatorio bajo carga con 0 fallos.

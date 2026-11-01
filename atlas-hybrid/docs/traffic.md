# Ciclo de vida de tráfico: validación antes de recibir carga y drenaje de instancias

- **Estado:** validado sobre GlassFish 8.0.5 en un clúster de dos instancias detrás de **HAProxy 2.8** (`runtime/traffic/traffic-demo.sh`, 23 verificaciones, dos ejecuciones seguidas)
- **Módulo:** `atlas-server-traffic` (extensión del servidor, HK2), ADR-011
- **Alcance del drenaje:** HTTP, JDBC y EJB (vía sus transacciones JTA)

## 1. El problema, medido

| Situación en GlassFish 8.0.5 sin el módulo | Medido |
|---|---|
| `stop-instance` con un POST transaccional de 4 s en curso | **HTTP 500**: la aplicación se descarga a mitad de la transacción |
| `stop-instance` con una operación de 12 s en curso | **Conexión cortada** a los ~4 s |
| Arranque de una instancia | `/health/ready` responde **UP** antes de cargar las aplicaciones: HAProxy envía peticiones y reciben **404** (32 de 402 en la prueba) |
| HAProxy con reintentos activados | Las peticiones cortadas **se reenvían a otra instancia**, incluidos POST: **ejecución duplicada** de la operación |

## 2. Estados de una instancia

```
          arranque                         validación correcta N veces seguidas
 STARTING ─────────► VALIDATING ───────────────────────────────────────► READY
 ready=DOWN          ready=DOWN                                            ready=UP
                       ▲                                                     │ atlas-drain
                       │ atlas-undrain (nunca directo a READY)                ▼
                    DRAINED ◄──────────── nada en curso ─────────────── DRAINING
                    (503, seguro parar)                                 ready=DOWN
```

| Estado | `/health/ready` | Peticiones nuevas |
|---|---|---|
| STARTING | DOWN (desde el primer instante del arranque) | Las atiende si llegan (el balanceador no las envía) |
| VALIDATING | DOWN | Ídem |
| READY | **UP** (si los checks de las aplicaciones también lo están) | Normal |
| DRAINING, periodo de gracia | DOWN | Se atienden con `Connection: close` |
| DRAINING, tras la gracia | DOWN | **503** + `Retry-After` + `Connection: close` |
| DRAINED | DOWN | 503 |

La consola de administración y `asadmin` (puerto de administración) y las rutas `/health*` nunca se rechazan ni se cuentan.

## 3. Validación antes de recibir carga

En VALIDATING, una ronda por segundo. La instancia pasa a READY tras **N rondas correctas seguidas** (`atlas.traffic.validation.successes`, por defecto 3). Un fallo reinicia la cuenta. Cada ronda comprueba:

1. **Todas las aplicaciones cargadas.**
2. **Todos los checks `@Readiness` de las aplicaciones en UP** (MicroProfile Health estándar).
3. **Consultas de solo lectura** a la base de datos, configuradas como propiedades de sistema (por clúster):

   ```bash
   asadmin create-system-properties --target <cluster> \
     'atlas.traffic.validation.1.resource=jdbc/clientes:atlas.traffic.validation.1.sql=SELECT 1 FROM DUAL'
   # .2.resource / .2.sql ... para más pools
   ```

   Solo lectura garantizada de tres formas: solo se aceptan sentencias únicas `SELECT`, `WITH` o `VALUES`; la conexión se pone en modo `readOnly`; y siempre se hace rollback.

El motivo del último fallo se ve en `asadmin atlas-traffic-status` y en `/health/ready` (`lastFailure`).

## 4. Drenaje

`asadmin atlas-drain [--grace 5] [--timeout 120] [--flushpools true] <instancia|clúster|server>`

1. Readiness a **DOWN** de inmediato.
2. **Gracia** (`--grace`): se siguen atendiendo peticiones, con `Connection: close`, mientras HAProxy detecta el cambio (2 sondeos × 1 s). Debe ser mayor que `fall × inter` del balanceador.
3. Después, las peticiones nuevas reciben **503**.
4. **Espera** hasta que no quede nada en curso:
   - peticiones HTTP (filtro de servidor en `default-web.xml`);
   - **transacciones JTA activas**: cubren los métodos de negocio EJB, los timers y los MDB. Requiere la monitorización de `transaction-service` en un nivel distinto de OFF al arrancar;
   - **conexiones JDBC en uso** en cada pool.
5. **Vaciado de los pools** (`--flushpools`): libera las sesiones de base de datos antes de parar (importante con Oracle).
6. **DRAINED.** Si vence `--timeout`, el comando termina con error e indica qué sigue en curso (por ejemplo `http=1, jta=1, jdbc=1`); la instancia sigue en DRAINING.

`asadmin atlas-undrain <destino>` la devuelve a VALIDATING. `asadmin atlas-traffic-status <destino>` muestra el estado, el tiempo en ese estado y el trabajo en curso **de cada instancia**.

## 5. HAProxy (`runtime/traffic/haproxy.cfg`)

```haproxy
frontend atlas
    acl idempotent method GET HEAD OPTIONS
    http-request disable-l7-retry if !idempotent      # nunca reenviar un POST cortado
    default_backend glassfish

backend glassfish
    option httpchk GET /health/ready
    http-check expect status 200
    timeout check 2s
    default-server inter 1s fall 2 rise 2
    server i1 10.0.0.11:28080 check port 28090      # sondeo al listener de salud dedicado
    server i2 10.0.0.12:29080 check port 29090
```

Reglas, con su motivo medido en la PoC:

| Regla | Por qué |
|---|---|
| **Listener de salud dedicado** en cada instancia (pool propio de 1–2 hilos) | Con el pool HTTP saturado, los sondeos se encolan, expiran y HAProxy expulsa **la última instancia sana**: hubo 10 670 respuestas 503 en 3 s en la prueba (findings H-11) |
| `disable-l7-retry` para métodos no idempotentes | Sin ella, HAProxy reenvió 5 peticiones cortadas a la otra instancia (H-12) |
| `retry-on conn-failure empty-response 503` + `option redispatch` | Las peticiones idempotentes que aún lleguen a una instancia que drena se reenvían sin error para el cliente |
| `fall × inter` < gracia del drenaje | HAProxy debe sacar la instancia antes de que empiece a responder 503 |

## 6. Reinicio rotatorio del clúster

```bash
runtime/traffic/rolling-restart.sh <clúster> [gracia=5] [timeout-drenaje=120] [timeout-ready=300]
ROLLING_HOOK='cp parche.jar …' runtime/traffic/rolling-restart.sh atlas-c1    # entre la parada y el arranque
```

Instancia por instancia: drenar → parar → (hook) → arrancar → **esperar a READY** → siguiente. Se detiene en la primera que no drena o no valida.

**Verificado:** reinicio de las dos instancias bajo carga continua (8 clientes, POST transaccionales de 4 s): **0 peticiones fallidas y 0 reenvíos de HAProxy**, en dos ejecuciones (157 y 162 peticiones).

## 7. Pools JDBC: validación de conexiones obligatoria

Tras una caída de la base de datos, un pool sin validación real conserva conexiones muertas: las peticiones fallan al usarlas, y una instancia que vuelve de VALIDATING no llega nunca a READY (H-13). La validación `meta-data` no basta. Para Oracle:

```bash
P=resources.jdbc-connection-pool.<pool>
asadmin set $P.validation-classname=org.glassfish.api.jdbc.validation.OracleConnectionValidation
asadmin set $P.connection-validation-method=custom-validation
asadmin set $P.is-connection-validation-required=true
asadmin set $P.fail-all-connections=true
```

## 8. Instalación

```bash
TARGET=<clúster> VALIDATION_RESOURCE=jdbc/clientes VALIDATION_SQL="SELECT 1 FROM DUAL" \
  runtime/traffic/configure-traffic.sh
asadmin create-system-properties --target <instancia> HEALTH_LISTENER_PORT=<puerto>   # en cada instancia
# reiniciar el clúster
```

El script:
1. copia `atlas-server-traffic.jar` a `glassfish/modules` (**en cada nodo**: es un módulo del servidor);
2. añade el filtro de drenaje a `default-web.xml`;
3. activa la monitorización de transacciones;
4. define la validación de solo lectura;
5. referencia el recurso JDBC;
6. crea el listener de salud dedicado.

Entorno de demostración completo: `runtime/traffic/setup-cluster-demo.sh` y después `runtime/traffic/traffic-demo.sh`.

## 9. Límites conocidos

- **Timers EJB:** su ejecución en curso se espera (cuenta como transacción), pero **no se pausan**. GlassFish no permite suspender el contenedor EJB (`EjbApplication.suspend()` devuelve "Not (yet) supported"), así que un timer puede dispararse durante el drenaje y alargarlo.
- **EJB remotos (IIOP) sin transacción y servlets asíncronos:** no se cuentan. Los asíncronos solo hasta que termina su primer *dispatch*.
- **JMS/MDB:** fuera del alcance acordado. Sus transacciones sí se esperan, pero la entrega de mensajes no se pausa.
- **Pools definidos dentro de las aplicaciones** (`@DataSourceDefinition`): no se cuentan; solo los pools del dominio.
- **Hay que copiar el módulo a cada nodo** del clúster (instalación de GlassFish de cada máquina).
- **APIs internas de GlassFish** (MicroProfile Health, PoolManager, gestor de transacciones): importaciones fijadas a `[8.0,9)`; revisar en cada versión mayor.

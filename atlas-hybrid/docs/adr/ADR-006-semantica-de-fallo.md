# ADR-006: Semántica de fallo: fallo inmediato, espera opcional y HTTP 503

- **Estado:** Aceptado
- **Fecha:** 2026-10-25

## Contexto

En un sistema dinámico, que falte un plugin es una **situación esperada y temporal** (actualización en curso, plugin retirado), no un error de programación. Hay que decidir qué ve el código de negocio y el cliente final cuando ocurre, sin bloquear hilos del servidor indefinidamente.

## Decisión

1. Por defecto, una llamada a través de `@OsgiService` sin servicio disponible **falla de inmediato** con `ServiceUnavailableException`, que es una `RuntimeException`.
2. Cada punto de inyección puede declarar `timeout` (ms) para **esperar** la llegada de un servicio. Es útil durante sustituciones, pero se usa con moderación, porque bloquea el hilo de la petición.
3. Las aplicaciones REST traducen `ServiceUnavailableException` a **HTTP 503** con `Retry-After` y un cuerpo JSON (`ServiceUnavailableMapper` en la demo), para que clientes y balanceadores puedan reintentar.
4. La aplicación **arranca aunque no haya plugins**, porque el proxy no toca OSGi hasta la primera llamada.
5. Las excepciones del plugin llegan **sin envolver** al llamante, con las mismas semánticas que una llamada local.
6. Si el contrato con el que está enlazada la aplicación ya no está activo, el mensaje lo dice explícitamente e indica la acción: redesplegar la aplicación.

## Alternativas consideradas

| Alternativa | En contra |
|---|---|
| Devolver `null` u `Optional` | Contamina el contrato de negocio con la dinámica de OSGi |
| Esperar siempre (bloqueo indefinido) | Agota hilos del servidor ante un plugin ausente |
| Fallar el despliegue si no hay plugin | Elimina el dinamismo y obliga a un orden rígido |

## Consecuencias

- El negocio decide dónde quiere un *fallback*: capturar la excepción o usar un servicio con filtro y timeout.
- La monitorización debe contar las `ServiceUnavailableException` por contrato (fase 1 de la hoja de ruta).

## Verificación

`runtime/demo.sh`: paso 4 (sin plugins → 503) y paso 5 (petición antes de que llegue el plugin → 200 gracias al timeout).

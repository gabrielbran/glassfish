# Registro de decisiones de arquitectura (ADR)

Cada ADR recoge una decisión significativa: su contexto, las alternativas valoradas y sus consecuencias. Los ADR no se reescriben: si una decisión cambia, se crea uno nuevo que **sustituye** al anterior y se actualiza el estado de este.

| ADR | Título | Estado |
|---|---|---|
| [ADR-001](ADR-001-distribucion-con-extensiones-no-fork.md) | Extensiones sobre GlassFish sin modificar, no un fork del core | Aceptado |
| [ADR-002](ADR-002-osgi-ds-para-plugins.md) | OSGi + Declarative Services para plugins de negocio; HK2 solo para el servidor | Aceptado |
| [ADR-003](ADR-003-contratos-como-bundles-versionados.md) | Contratos como bundles independientes, versionados y nunca empaquetados en la aplicación | Aceptado |
| [ADR-004](ADR-004-puente-cdi-propio-y-delgado.md) | Puente CDI → OSGi propio y delgado, en lugar de migrar FighterFish o usar OSGi CDI | Aceptado |
| [ADR-005](ADR-005-configuracion-dominio-sin-tocar-core.md) | Perfiles `JavaSE/compact` mediante una opción JVM del dominio | Aceptado (provisional, hasta la corrección upstream) |
| [ADR-006](ADR-006-semantica-de-fallo.md) | Semántica de fallo: fallo inmediato, espera opcional y HTTP 503 | Aceptado |
| [ADR-007](ADR-007-persistencia-en-plugins.md) | Persistencia en plugins: `DataSource` del servidor por JNDI dentro de la transacción JTA del llamante | Aceptado (JPA en plugin: pendiente de validar) |

## Plantilla

```markdown
# ADR-NNN: Título en imperativo

- **Estado:** Propuesto | Aceptado | Sustituido por ADR-XXX | Obsoleto
- **Fecha:** AAAA-MM-DD
- **Decisores:** ...

## Contexto
¿Qué problema o fuerza motiva la decisión?

## Decisión
¿Qué decidimos? (frases cortas y verificables)

## Alternativas consideradas
| Alternativa | A favor | En contra |

## Consecuencias
Positivas, negativas y lo que pasa a ser obligatorio.

## Verificación
¿Cómo comprobamos que se cumple? (test, revisión, check de CI)
```

# Registro de decisiones de arquitectura

Los ADR aceptados son contratos del sistema. Un cambio incompatible exige un ADR nuevo que
marque al anterior como reemplazado; editar silenciosamente una decisión histórica no es
válido.

| ADR | Estado | Decisión |
| --- | --- | --- |
| [0001](0001-unidades-coordenadas-y-transformaciones.md) | Aceptada | Unidades, ejes y serialización de transformaciones |
| [0002](0002-frontera-spring-worker.md) | Aceptada | Frontera Spring ↔ worker mediante PostgreSQL y filesystem |
| [0003](0003-ownership-y-orden-de-seguridad.md) | Aceptada | Ownership y seguridad antes de recursos privados |
| [0004](0004-snapshot-inmutable-combined-export.md) | Aceptada | Snapshot inmutable para Combined Export |
| [0005](0005-semantica-de-jobs.md) | Aceptada | Estados, lease, reintentos e idempotencia de jobs |
| [0006](0006-validez-geometrica.md) | Aceptada | Criterios medibles y diagnósticos geométricos |

## Cómo leerlos

Cada documento separa decisiones firmes, valores iniciales configurables y cuestiones
aplazadas. Los defaults operativos protegen el MVP, pero deben calibrarse con métricas.

# ADR-0005 — Semántica de jobs geométricos

**Estado:** Aceptada

**Fecha:** 2026-08-11

## Decisión

PostgreSQL implementa una cola persistente de entrega **al menos una vez**. Claims cortos,
lease renovable, salida idempotente y fencing token evitan mantener locks durante geometría
y evitan que un worker rezagado seleccione o sobrescriba el artefacto de un intento posterior.

## Estados y transiciones

```text
PENDING ------claim------> RUNNING ------success------> COMPLETED
   ^                         |
   |                         +--non-retryable/exhausted--> FAILED
   +----- RETRY_WAIT <-------+--retryable/lease expired
```

Estados firmes: `PENDING`, `RUNNING`, `RETRY_WAIT`, `COMPLETED`, `FAILED`. Solo
`COMPLETED` y `FAILED` son terminales. Cancelación se aplaza; no se simula con `FAILED`.

## Claim y lease

En una transacción corta, el worker selecciona un job elegible con orden estable
`priority DESC, available_at ASC, created_at ASC, id ASC`, `FOR UPDATE SKIP LOCKED`, y lo
actualiza:

```text
status = RUNNING
attempt_count += 1
claim_token = UUID aleatorio nuevo
worker_id = identificador opaco
lease_expires_at = statement_timestamp() + lease_duration
started_at = COALESCE(started_at, statement_timestamp())
```

Después confirma la transacción y procesa sin locks. Heartbeat y finalización hacen update
condicional por `id`, `status = RUNNING` y `claim_token`; un token antiguo no puede renovar
ni publicar estado.

Claim, heartbeat, recuperación y finalización usan exactamente `statement_timestamp()` de
PostgreSQL y comparan o calculan leases dentro de SQL; no envían
la hora del worker. Se elige `statement_timestamp()` —inicio de la sentencia actual— en vez
de `CURRENT_TIMESTAMP`/`transaction_timestamp()`, que quedan fijados al inicio de una
transacción y podrían estar obsoletos en una transacción abierta durante más tiempo.

```sql
lease_expires_at = statement_timestamp() + :lease_duration
-- elegibilidad de recuperación
lease_expires_at <= statement_timestamp()
```

## Valores iniciales configurables

| Parámetro | Default inicial | Motivo |
| --- | --- | --- |
| `lease_duration` | 120 s | Tolera pausas cortas sin demorar demasiado la recuperación. |
| `heartbeat_interval` | 30 s | Cadencia nominal con margen frente al lease. Debe ser `< lease/3`; el scheduler no garantiza un número concreto de heartbeats. |
| `max_attempts` | 3 | Acota coste geométrico; se calibra por tipo de job. |
| backoff | exponencial con full jitter, base 15 s, máximo 15 min | Evita thundering herd sin ocultar fallos durante horas. |
| batch de claim | 1 por worker | La geometría es intensiva en memoria; subirlo exige medición. |

Los tiempos usan reloj de PostgreSQL, no el del contenedor worker. Cambiar estos valores no
altera el modelo de estados.

## Reintentos y recuperación

- Errores transitorios declarados (`DATABASE_UNAVAILABLE`, I/O temporal, worker terminado)
  pasan a `RETRY_WAIT` con `available_at` calculado.
- Payload incompatible, checksum distinto, input geométrico inválido y límites excedidos son
  no reintentables.
- Un reconciliador recupera `RUNNING` con lease expirado. Si quedan intentos, pasa a
  `RETRY_WAIT`; si no, a `FAILED` con `LEASE_EXPIRED_ATTEMPTS_EXHAUSTED`.
- Cada intento registra inicio, fin, código estable, mensaje sanitizado y diagnóstico JSON.
  No se persisten stack traces o paths privados como respuesta de usuario.

## Idempotencia y publicación

- Spring asigna `idempotency_key` por operación lógica y aplica una restricción única por
  `(owner_id, job_type, idempotency_key)`.
- Repetir la solicitud devuelve el job existente si payload y snapshot hash coinciden; si no,
  responde conflicto.
- El artefacto se construye bajo `/data/tmp/{job_id}/{claim_token}/` y se publica por rename
  atómico a un path final inmutable que incluye el token, por ejemplo
  `/data/exports/{export_id}/attempts/{claim_token}/artifact.stl`. Ningún intento escribe en
  el path de otro token ni en un nombre final compartido.
- Tras publicar y verificar el checksum, el worker intenta seleccionar ese candidato con un
  único `UPDATE` condicional por `id`, `status = RUNNING`, `claim_token` y lease vigente:

  ```sql
  UPDATE geometry_jobs
     SET status = 'COMPLETED',
         output_storage_key = :immutable_key,
         output_sha256 = :sha256,
         completed_at = statement_timestamp()
   WHERE id = :job_id
     AND status = 'RUNNING'
     AND claim_token = :claim_token
     AND lease_expires_at > statement_timestamp();
  ```

- Una fila actualizada convierte ese path en el ganador. Cero filas significa claim vencido
  o reemplazado: el worker no modifica el job y su publicación queda como orphan limpiable.
  Así un worker rezagado puede consumir CPU o dejar un candidato no referenciado, pero nunca
  sobrescribe ni selecciona el artefacto vigente.
- Repetir la finalización con el mismo token consulta primero la fila: si ya referencia la
  misma storage key y checksum, es éxito idempotente. La limpieza solo elimina temporales o
  candidatos que ninguna fila referencia y respeta un periodo de gracia.
- Efectos externos adicionales deben usar outbox; el MVP no envía notificaciones desde el
  worker.

## Consecuencias

- La entrega al menos una vez implica que el procesamiento puede repetirse; la seguridad
  depende de idempotencia y fencing, no de esperar ejecución exactamente una vez.
- `SKIP LOCKED` es apropiado para la cola, no para consultas generales.
- La selección autoritativa vive en PostgreSQL; la atomicidad del rename protege cada
  candidato individual, no coordina intentos concurrentes.

## Cuestiones aplazadas

Prioridades de producto, cancelación cooperativa, límites por usuario, dead-letter queue y
separación por pools. Se añaden solo con casos de uso y estados explícitos.

## Fuentes

- [PostgreSQL 18: locking y `SKIP LOCKED`](https://www.postgresql.org/docs/18/sql-select.html)
- [PostgreSQL 18: funciones de fecha/hora del servidor](https://www.postgresql.org/docs/18/functions-datetime.html)
- [ADR-0002 — frontera y layout de storage](0002-frontera-spring-worker.md)

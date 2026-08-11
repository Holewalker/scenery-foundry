# ADR-0002 — Frontera Spring ↔ Geometry Worker

**Estado:** Aceptada

**Fecha:** 2026-08-11

## Decisión

Spring y el worker se coordinan mediante **PostgreSQL + filesystem compartido**. No existe
API HTTP interna en el MVP. Spring es propietario del esquema y crea jobs; Python consume
un contrato versionado, procesa fuera de transacción y publica artefactos derivados.

## Contrato firme

```text
Spring --INSERT geometry_jobs(payload JSONB)--> PostgreSQL
Worker --claim/status/diagnostics-----------> PostgreSQL
Spring y Worker --storage keys--------------> /data
```

- Flyway, ejecutado por Spring, es el único migrador de esquema.
- El worker usa una cuenta de base de datos separada con permisos mínimos sobre las tablas y
  secuencias de jobs; no lee tablas de credenciales ni decide autorización.
- El servidor genera identificadores UUID y **storage keys relativas**; nunca acepta una key
  ni un nombre de archivo del cliente. Tras una única decodificación estricta se rechazan
  paths absolutos, segmentos vacíos, `.`/`..`, separadores codificados o residuales y bytes
  nulos. Cada key se resuelve desde un descriptor de la raíz `/data`, no por concatenación.
  La apertura/publicación recorre componentes sin seguir symlinks (`openat2` con
  `RESOLVE_BENEATH|RESOLVE_NO_SYMLINKS`, o recorrido `openat` equivalente con `O_NOFOLLOW`)
  y verifica containment antes de tocar el artefacto.
- Spring valida ownership y congela los datos necesarios antes de crear el job.
- El worker verifica checksums antes de procesar y cada intento escribe únicamente bajo
  `/data/tmp/{job_id}/{claim_token}/`.
- Cada intento publica, dentro del mismo filesystem, a un destino **inmutable y exclusivo
  del token**, por ejemplo
  `/data/exports/{export_id}/attempts/{claim_token}/artifact.stl`. Nunca sobrescribe un path
  existente: usa `renameat2(..., RENAME_NOREPLACE)` cuando kernel y filesystem lo soportan.
  El fallback POSIX crea el nombre mediante `linkat` del temporal en el mismo mount —que
  falla con `EEXIST`—, hace `fsync` del fichero y directorio y solo entonces elimina el
  temporal. No se permite degradar a `rename()` con reemplazo; una colisión de token falla.
- PostgreSQL selecciona el artefacto vigente mediante la finalización condicional definida en
  [ADR-0005](0005-semantica-de-jobs.md). `COMPLETED` solo se confirma después de verificar el
  checksum y enlazar la storage key ganadora. Temporales y publicaciones no referenciadas se
  limpian posteriormente de forma idempotente.

## Envelope del payload

```json
{
  "contract": "scenery-foundry.geometry-job",
  "version": 1,
  "jobType": "COMBINED_EXPORT",
  "jobId": "uuid",
  "subjectId": "uuid",
  "input": {},
  "output": {},
  "options": {}
}
```

`contract`, `version`, `jobType` y `jobId` son obligatorios. Cada `jobType` tiene un schema
JSON versionado y fixtures de productor/consumidor. Campos desconocidos se ignoran solo si
el schema los marca como extensibles; falta o incompatibilidad de un campo obligatorio
produce `UNSUPPORTED_PAYLOAD_VERSION` sin reintento.

Cambios aditivos compatibles conservan `version`. Cambios de semántica, unidad, campo
obligatorio o representación incrementan la versión. Spring puede emitir únicamente
versiones que el worker desplegado anuncia como soportadas en su metadata de arranque.

## Compatibilidad de despliegue

1. migración expand-only compatible con versión anterior;
2. worker capaz de leer versión antigua y nueva;
3. backend que comienza a producir la versión nueva;
4. retirada posterior del contrato antiguo.

No se elimina una columna o versión mientras existan jobs no terminales que la necesiten.

## Consecuencias

- Se evita operar y autenticar un servicio interno adicional.
- PostgreSQL es a la vez persistencia y cola ligera; el contrato de jobs debe ser más
  disciplinado que una llamada de función interna.
- Compartir filesystem exige que backend y worker monten el mismo volumen y que `tmp` y el
  destino final estén en el mismo filesystem para conservar atomicidad.
- El filesystem conserva candidatos inmutables; la fila de PostgreSQL es la autoridad que
  decide cuál de ellos pertenece al resultado del job.

## Valores configurables y cuestiones aplazadas

- **Configurable:** raíz física `/data`, tiempos de limpieza y permisos de la cuenta worker.
- **Aplazado:** object storage externo, API worker, broker de mensajes y ejecución distribuida.
  Solo se reconsideran con una métrica de escala o disponibilidad.

## Fuentes

- [PostgreSQL 18: `SKIP LOCKED` para consumidores de tablas tipo cola](https://www.postgresql.org/docs/18/sql-select.html)
- [PostgreSQL 18: tipo JSON](https://www.postgresql.org/docs/18/datatype-json.html)
- [Linux: `openat2` y resolución restringida](https://man7.org/linux/man-pages/man2/openat2.2.html)
- [Linux: `renameat2` y `RENAME_NOREPLACE`](https://man7.org/linux/man-pages/man2/renameat2.2.html)

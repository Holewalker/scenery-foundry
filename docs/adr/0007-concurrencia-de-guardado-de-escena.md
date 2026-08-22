# ADR-0007 — Concurrencia de guardado de escena y semántica de autosave

**Estado:** Aceptada

**Fecha:** 2026-08-22

## Decisión

El guardado de escena deja de ser **último escritor gana**. `projects` gana una columna
`scene_version bigint NOT NULL DEFAULT 0`. `PUT /api/projects/{id}/scene` transporta la
versión que el cliente observó por última vez; el servidor la valida con un `UPDATE`
condicional dentro de la transacción existente y responde **409** cuando otro escritor ya
avanzó. El cliente guarda solo (autosave con debounce + single-flight); el botón Save deja de
ser el único disparador.

## Contrato HTTP

```text
GET  /api/projects/{id}/scene  -> 200 {"version": N, "objects": [...]}
PUT  /api/projects/{id}/scene     {"version": N, "objects": [...]}
                               -> 200 {"version": N+1, "objects": [...]}
                               -> 409 {"code":"SCENE_VERSION_CONFLICT","message":...}
                               -> 400 {"code":"...","message":...}   validación de escena
                               -> 404                                proyecto ajeno o inexistente
```

Un único campo `version` sirve para petición y respuesta: en la petición significa "la versión
que observé", en la respuesta "la versión ahora almacenada". El cliente devuelve siempre lo
último que el servidor le entregó, así que no necesita dos nombres. `409` (no `412`) porque el
conflicto se detecta sobre el estado del recurso, no sobre una precondición de cabecera; se
mantiene la forma de error `{code, message}` ya establecida (`ApiExceptionHandler`).

## Orden de comprobaciones

1. **Ownership primero** (ADR-0003): un proyecto ajeno responde `404`, nunca `409`. Un 409
   sobre un proyecto que no es tuyo filtraría su existencia.
2. Validación de escena (cardinalidad ≤ 250, ids únicos, assets READY, coherencia
   `translationMm`/matriz) — `400`.
3. `UPDATE` condicional de versión — `409`.
4. `delete`+`insert` de `scene_objects`.

El `UPDATE` de `projects` se ejecuta **antes** del `delete`/`insert`, no después: toma el row
lock de `projects` y serializa a los escritores concurrentes del mismo proyecto antes de tocar
`scene_objects`. Bajo `READ COMMITTED` PostgreSQL vuelve a evaluar el predicado contra la
versión vigente tras esperar el lock —el mismo patrón que ADR-0005 usa para la finalización
condicional de jobs—, de modo que el segundo escritor ve cero filas y falla rápido sin haber
reescrito la escena. No se admite un `SELECT` de versión seguido de un `UPDATE`
incondicional.

```sql
UPDATE projects
   SET scene_version = scene_version + 1
 WHERE id = :project
   AND scene_version = :expected
RETURNING scene_version;
```

Cero filas devueltas significa conflicto. La transacción sigue siendo la existente
(`@Transactional(isolation = READ_COMMITTED)` en `JdbcOwnedSceneRepository.replaceScene`); no
se añade ninguna transacción ni servicio nuevo.

## Semántica de autosave (cliente)

- **Debounce**: 2 s de inactividad, con techo de 15 s desde la primera mutación sucia, para
  que un arrastre continuo también persista.
- **Single-flight**: un solo PUT en vuelo; las mutaciones que ocurren durante el vuelo se
  fusionan en un único envío posterior. El PUT reemplaza la escena completa y por tanto es
  idempotente, así que fusionar es correcto por construcción.
- **Contador de revisión**: cada acción mutadora incrementa un contador monótono. `dirty` solo
  se limpia si el contador no cambió durante el vuelo; si cambió, se rearma el debounce.
- **409**: se suspende el autosave y se bloquea el guardado. La única salida en v1 es recargar
  la escena del servidor. **No existe "sobrescribir igualmente"**: reintroduciría exactamente
  la pérdida de datos silenciosa que este ADR elimina. Varias pestañas del mismo usuario son un
  flujo real, así que el conflicto se presenta como diálogo modal, no como aviso secundario.
- **Red/5xx**: reintento acotado con backoff exponencial y jitter; agotado, estado offline con
  los cambios locales intactos.
- **4xx de validación**: se muestra el mensaje y se detiene el autosave; reintentar el mismo
  cuerpo no puede tener éxito.

## Valores iniciales configurables

| Parámetro | Default inicial | Motivo |
| --- | --- | --- |
| debounce inactivo | 2 s | Ventana de pérdida aceptada por producto. |
| techo de debounce | 15 s | Un arrastre largo persiste sin esperar a soltar. |
| reintentos de red | 5 | Cubre un corte breve sin martillear el servidor. |
| backoff | exponencial con full jitter, base 1 s, máximo 16 s | Coherente con ADR-0005. |
| `app.scene.require-version` | `false` en la release transitoria, `true` después | Compatibilidad, ver abajo. |

Cambiar estos valores no altera el contrato HTTP ni el modelo de conflicto.

## Compatibilidad de despliegue

Se aplica la secuencia expand-only de ADR-0002:

1. Migración `V7` aditiva: columna con `DEFAULT 0`, ningún cliente afectado.
2. Release transitoria: `version` ausente o `null` se acepta como escritura **no verificada**
   (el servidor lee la versión vigente y la usa como esperada). Comportamiento idéntico al
   actual para clientes antiguos.
3. Cliente nuevo desplegado, siempre envía `version`.
4. `app.scene.require-version=true`: `version` ausente responde `400`.

No se retira el modo no verificado mientras exista un cliente desplegado sin el campo.

## Consecuencias

- La detección de conflictos es por escena completa, no por objeto. Dos pestañas que editan
  objetos distintos también colisionan. Es el precio de un PUT de escena completa y es
  aceptable frente a la pérdida silenciosa actual.
- El autosave multiplica el tráfico de PUT. El debounce y el single-flight lo acotan a ~1
  petición cada 2 s por cliente; el endpoint ya está limitado a 250 objetos. No se añade rate
  limiting en esta fase.
- `scene_version` es el contador autoritativo de la escena y queda disponible para futuras
  funciones (historial, detección de cambio en polling) sin otra migración.
- Un `409` seguido de recarga descarta trabajo local del usuario. Es una pérdida **visible y
  consentida**, no silenciosa; ese es el cambio de política.

## Cuestiones aplazadas

Merge por objeto o por campo, resolución de conflictos en servidor, transformación operacional
o CRDT, colaboración en tiempo real, cola de escrituras offline, historial/undo persistido,
autosave de recursos distintos de la escena y rate limiting por usuario. Cada una exige un caso
de uso explícito y su propio ADR.

## Fuentes

- [PostgreSQL 18: `UPDATE` y `RETURNING`](https://www.postgresql.org/docs/18/sql-update.html)
- [PostgreSQL 18: niveles de aislamiento y re-evaluación bajo lock](https://www.postgresql.org/docs/18/transaction-iso.html)
- [RFC 9110 §15.5.10 — 409 Conflict](https://www.rfc-editor.org/rfc/rfc9110#name-409-conflict)
- [ADR-0003 — ownership antes de recursos privados](0003-ownership-y-orden-de-seguridad.md)
- [ADR-0005 — update condicional y fencing](0005-semantica-de-jobs.md)

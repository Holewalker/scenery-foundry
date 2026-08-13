# ADR-0004 — Snapshot inmutable de Combined Export

**Estado:** Aceptada

**Fecha:** 2026-08-11

## Decisión

Cada solicitud de Combined Export crea, dentro de una transacción, un snapshot inmutable y
versionado. El worker procesa ese snapshot; nunca relee el estado vivo del proyecto para
decidir qué exportar.

## Contenido mínimo del snapshot v1

```text
snapshot_version
export_id, project_id, owner_id, captured_at
ordered objects:
  scene_object_id
  asset_id
  original_storage_key
  original_sha256
  matrix_contract_version
  matrix_world_column_major[16]
options:
  boolean_engine + version
  geometry_policy_version
  requested_output_format
```

El MVP no persiste un orden de escena independiente: Spring ordena la lista completa por
`scene_object_id ASC` antes de serializarla. Spring calcula
`snapshot_sha256` sobre la serialización canónica versionada
`scenery-foundry.snapshot-jcs/v1`, que aplica **RFC 8785 (JCS)** al snapshot y guarda tanto el
identificador del canonicalizador como el hash junto al job.

### Canonicalización `scenery-foundry.snapshot-jcs/v1`

1. La implementación actual recibe únicamente bytes UTF-8 de JSON sin procesar y los transforma
   en bytes UTF-8 RFC 8785 antes del hash. El límite público v1 no acepta modelos, diccionarios,
   strings de conveniencia ni constructores numéricos. Se restringe al dominio I-JSON: strings
   Unicode válidos, booleanos, `null` y números finitos representables por IEEE-754 binary64.
   `NaN`, infinito, claves duplicadas y datos Unicode inválidos se rechazan antes del hash.
2. JCS serializa números y escapes de strings según ECMAScript, sin whitespace opcional.
   No se redondean, formatean ni convierten números mediante reglas propias.
3. Las propiedades de **cada** objeto se ordenan lexicográficamente por sus unidades de
   código UTF-16, de forma recursiva. Los arrays preservan su orden; por eso el orden de
   objetos de escena forma parte del contrato.
4. Las strings se conservan tal como llegan: no se aplica normalización Unicode implícita.
5. Los bytes hasheados son exactamente el JSON canónico codificado en UTF-8, sin BOM ni
   salto de línea final. `snapshot_sha256 = lowercase_hex(SHA-256(bytes))`.

Cambiar cualquiera de estas reglas exige un identificador de canonicalizador nuevo. Un
fixture común Java/Python contiene casos con claves anidadas, caracteres no ASCII, escapes,
números límite y arrays ordenados, más el byte stream y SHA-256 esperados.

La conversión de un snapshot de dominio a ese JSON, así como la persistencia de snapshots,
schema, fencing, polling, ownership y autenticación, permanece aplazada. Esta entrega solo
implementa validación, canonicalización y digest en el límite de bytes crudos.

## Contrato firme

- Spring valida ownership de proyecto, objetos y assets en la misma transacción que captura
  el snapshot.
- Assets no preparados, checksums ausentes o transformaciones inválidas impiden crear el
  job.
- El snapshot no se modifica. Reintentos del mismo job usan exactamente el mismo snapshot.
- El worker verifica `original_sha256` antes de procesar. Una discrepancia produce fallo
  terminal `INPUT_CHECKSUM_MISMATCH`, no una sustitución silenciosa.
- Cambios posteriores en proyecto, transforms, assets, configuración o motor no afectan al
  export existente. El usuario solicita un export nuevo para capturarlos.
- El artefacto y el reporte final registran `snapshot_sha256`, canonicalizador, plataforma,
  arquitectura, versiones efectivas de librerías, checksums de entrada, política geométrica
  y checksum de salida.

## Consecuencias

- Los inputs y la decisión de exportación son identificables y auditables aunque la escena
  continúe editándose.
- Se duplica metadata, no blobs STL. Ese coste es deliberado.
- El mismo snapshot no promete geometría ni bytes idénticos bajo motores, versiones o
  plataformas diferentes. La reproducción geométrica dentro de tolerancias requiere
  conservar o reconstruir el entorno registrado y ejecutar la política versionada.

## Valores configurables y cuestiones aplazadas

- **Configurable:** retención de snapshots y artefactos, pero nunca se borra un snapshot
  mientras su export exista.
- **Aplazado:** reproducción bit-a-bit entre plataformas; el MVP exige equivalencia
  geométrica dentro de tolerancias y provenance completo, no bytes STL idénticos.

## Fuentes relacionadas

- [RFC 8785 — JSON Canonicalization Scheme](https://www.rfc-editor.org/rfc/rfc8785)
- [ADR-0001 — transformaciones](0001-unidades-coordenadas-y-transformaciones.md)
- [ADR-0005 — publicación fenced de artefactos](0005-semantica-de-jobs.md)
- [ADR-0006 — validez geométrica](0006-validez-geometrica.md)

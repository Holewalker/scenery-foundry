# ADR-0006 — Validez geométrica medible

**Estado:** Aceptada

**Fecha:** 2026-08-11

## Decisión

“STL válido” y “Combined Export válido” significan superar checks medibles y producir un
reporte versionado. Poder parsear o escribir un STL no demuestra que represente un volumen
imprimible.

## Dos estados independientes del asset

- `processing_status`: `UPLOADED | PROCESSING | READY | FAILED`.
- `geometry_status`: `UNKNOWN | VALID_VOLUME | INVALID_VOLUME`.

Un STL parseable puede estar `READY + INVALID_VOLUME`: se puede previsualizar, pero no entra
en Combined Export hasta que exista una reparación explícita y trazable. El original nunca
se modifica.

## Checks de input v1

Un input es elegible para booleanos solo si:

1. el tamaño y número de triángulos están dentro de límites;
2. Trimesh carga exactamente una malla triangular no vacía;
3. vértices y caras son finitos, índices están en rango y toda cara referencia tres vértices
   distintos;
4. ninguna cara es degenerada según el umbral de área;
5. cada arista pertenece exactamente a dos caras (`is_watertight`);
6. el winding es consistente y las normales miran hacia fuera (`is_volume`);
7. el volumen firmado es positivo y superior al umbral numérico;
8. Manifold3D construye el objeto con `status() == Error.NoError`.

El cargador conserva bytes y carga con procesamiento automático deshabilitado. Sobre una
copia de análisis construye después una topología indexada agrupando solo vértices con las
mismas coordenadas binary64 decodificadas; el default de soldado es tolerancia cero. Los
checks 3–8 operan sobre esa copia y registran conteos antes/después. Una tolerancia positiva
sería una reparación, exige otra versión de política y un derivado trazable; nunca modifica
ni sustituye el original.

Manifold define manifoldness topológica exigiendo que cada arista de triángulo tenga
exactamente una arista pareja con los vértices en orden inverso, y que los triángulos estén
orientados en sentido antihorario vistos desde fuera. El estado `NoError` confirma aceptación
por el kernel bajo ese contrato; no sustituye los demás checks de esta política.

## Checks de Combined Export v1

Antes del booleano se vuelven a verificar checksums, matrices y elegibilidad de cada input.
Después de aplicar transforms y ejecutar Manifold3D, el resultado debe:

- ser no vacío, finito, triangular y estar dentro de límites;
- superar los checks 3–8 anteriores;
- conservar bounds y volumen finitos;
- exportarse, recargarse desde el STL producido y volver a superar los checks;
- conservar bounds y volumen frente a la referencia cuantizada descrita abajo tras reload.

El STL binario almacena coordenadas `float32`. Antes de escribirlo, el worker crea una
referencia con los mismos vértices redondeados a float32 y calcula `q`, el máximo ULP float32
de cualquier componente finito con magnitud `max(1 mm, |coordenada|)`. Cada componente de
`bounds_min` y `bounds_max` recargado debe diferir de esa referencia como máximo
`max(1e-5 mm, 4q)`. La diagonal de cada AABB es
`sqrt((max_x-min_x)^2 + (max_y-min_y)^2 + (max_z-min_z)^2)` y se registra para input,
resultado previo y referencia cuantizada.

Para volumen, `V_before`, `V_quantized` y `V_reload` son valores absolutos. Se registra el
efecto inevitable `abs(V_quantized-V_before)/V_before`; la integridad del reload exige
`abs(V_reload-V_quantized)/V_quantized <= max(1e-12, 64*eps64*kappa)`, donde `kappa` es la
suma absoluta de las contribuciones tetraédricas usada para el volumen dividida por
`V_quantized` y `eps64 = 2^-52`. Volúmenes no positivos ya fallaron antes. Así la política mide la
cuantización esperada sin imponer a STL precisión binary64.

Se permiten componentes desconectados, pero se emite `DISCONNECTED_COMPONENTS` con su
cantidad. El producto podrá exigir una sola componente por tipo de export en una decisión
posterior; el MVP no descarta geometría silenciosamente.

Manifold garantiza salida manifold para operaciones booleanas sobre inputs manifold dentro
de su modelo numérico. Esa garantía **no equivale a imprimibilidad**: no demuestra grosor
mínimo, tolerancias de ensamblaje, overhangs, tamaño de cama, material, configuración de
slicer ni éxito físico. El MVP separa por ello aceptación del motor, validación de esta
política y la comprobación manual del spike en un slicer.

## Umbrales iniciales configurables

Estos defaults son guardrails para un único VPS, no propiedades universales de STL:

| Límite | Default | Comportamiento |
| --- | --- | --- |
| Upload STL | 200 MiB | rechazo `FILE_TOO_LARGE` antes de parsear |
| Triángulos por asset | 2 000 000 | rechazo `TRIANGLE_LIMIT_EXCEEDED` |
| Objetos por Combined Export | 250 | rechazo antes de crear el job |
| Triángulos de entrada acumulados | 5 000 000 | rechazo `COMBINED_INPUT_LIMIT_EXCEEDED` |
| Triángulos de salida | 5 000 000 | fallo `COMBINED_OUTPUT_LIMIT_EXCEEDED` |
| Valor absoluto de coordenada | 1 000 000 mm | rechazo de unidad/orientación probablemente errónea |
| Área degenerada | `max(1e-12 mm², diagonal² × 1e-16)` | cara inválida |
| Volumen mínimo | `max(1e-12 mm³, diagonal³ × 1e-15)` | volumen numéricamente nulo |

Los límites se aplican tanto en Spring cuando puede hacerlo sin parsear como en worker de
forma autoritativa. Cambiarlos exige registrar métricas de memoria, tiempo y corpus afectado.

## Diagnóstico obligatorio

El reporte JSON incluye `geometryPolicyVersion`, librerías, checksum, contadores, bounds,
volumen, número de componentes y una lista ordenada de diagnósticos:

```json
{
  "code": "NOT_WATERTIGHT",
  "severity": "ERROR",
  "count": 42,
  "messageKey": "geometry.not_watertight",
  "details": {}
}
```

Los códigos son estables; el texto se localiza fuera del worker. `ERROR` invalida;
`WARNING` conserva validez pero requiere visibilidad; `INFO` aporta métricas. Nunca se marca
un export `COMPLETED` sin persistir el reporte final y su checksum.

Antes de persistir o calcular el checksum, se agregan duplicados y se ordena por
`(severity_rank, code, JCS(details), messageKey, count)`, con
`ERROR=0`, `WARNING=1`, `INFO=2` y orden ascendente en el resto de campos.

El reporte conserva el valor bruto de `Manifold.status()` y lo mapea a un código estable.
El scaffold fija Manifold3D 3.5.2 y su test exige exactamente estos 15 miembros exportados:
`NoError`, `NonFiniteVertex`, `NotManifold`, `VertexOutOfBounds`, `PropertiesWrongLength`,
`MissingPositionProperties`, `MergeVectorsDifferentLengths`, `MergeIndexOutOfBounds`,
`TransformWrongLength`, `RunIndexWrongLength`, `FaceIDWrongLength`, `InvalidConstruction`,
`ResultTooLarge`, `InvalidTangents` y `Cancelled`. Solo `NoError` permite continuar; cada
valor conocido restante produce su diagnóstico y cualquier valor desconocido se mapea a
`MANIFOLD_STATUS_UNKNOWN`/`ERROR`.
Nunca existe un default de éxito ni puede alcanzarse `COMPLETED` con un estado desconocido.

## Consecuencias

- El pipeline puede rechazar STL que otras herramientas toleran o reparan implícitamente;
  el reporte explica cada rechazo sin alterar el original.
- La aceptación topológica de Manifold es una señal necesaria, no un certificado de
  imprimibilidad. El spike conserva la apertura en un slicer real como evidencia separada.
- Cualquier reparación futura aumenta storage y provenance, porque genera un derivado
  independiente en lugar de mutar el asset original.

## Cuestiones aplazadas

- Reparación automática, tolerancia de soldado, detección exhaustiva de autointersecciones,
  grosor mínimo, overhangs y restricciones de una impresora concreta.
- Blender como fallback. Debe probar una clase de inputs que no pueda resolverse con el
  pipeline actual y mantener la misma política de reporte.

## Fuentes

- [Trimesh: `is_watertight`, `is_winding_consistent` e `is_volume`](https://trimesh.org/trimesh.html)
- [Trimesh quick start y carga sin procesamiento](https://trimesh.org/quick_start.html)
- [Trimesh 5.0.0: carga y export STL binario `float32`](https://github.com/mikedh/trimesh/blob/5.0.0/trimesh/exchange/stl.py)
- [Manifold 3.5.2 — enum `Error` exportado por Python](https://github.com/elalish/manifold/blob/v3.5.2/bindings/python/manifold3d.cpp)
- [Manifold — requisitos topológicos e interoperabilidad](https://github.com/elalish/manifold/wiki/Manifold-Library)

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

El cargador se ejecuta con procesamiento automático deshabilitado para que la inspección no
repare silenciosamente el original. Una normalización futura genera un derivado separado,
con operaciones y checksum registrados.

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
- conservar bounds con error máximo
  `max(1e-5 mm, diagonal_resultado × 1e-10)` y volumen relativo `<= 1e-8` tras reload.

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

El reporte conserva además el valor bruto de `Manifold.status()` y lo mapea a un código
estable propio. La auditoría documental consultada confirmó `Error.NoError` y
`Error.Cancelled`, pero no expuso una lista exhaustiva y versionada del enum; el scaffold
debe fijar Manifold3D 3.5.2 y generar un test de contrato contra los valores realmente
exportados por esa versión antes de publicar el mapeo completo.

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
- [Manifold — repositorio y documentación oficial](https://github.com/elalish/manifold)
- [Manifold — requisitos topológicos e interoperabilidad](https://github.com/elalish/manifold/wiki/Manifold-Library)

# ADR-0001 — Unidades, coordenadas y transformaciones

**Estado:** Aceptada

**Fecha:** 2026-08-11

## Decisión

Toda geometría de dominio usa **milímetros**, un sistema cartesiano **diestro** y **Y-up**.
La transformación autoritativa de cada instancia es una matriz afín 4×4 `float64`, aplicada
a vectores columna: `p_world = M_world · p_asset`. Su representación JSON es el array
column-major de 16 números leído de `THREE.Matrix4.elements` por el adaptador del frontend.

Three.js documenta el almacenamiento interno column-major de `Matrix4`. El resto de esta
sección —orden del array JSON, reconstrucción NumPy, composición y aplicación a puntos— es
un **contrato propio de los adaptadores** y queda demostrado por el fixture cruzado; no se
deduce automáticamente de que Three.js o Trimesh acepten una matriz 4×4.

## Contrato firme

| Tema | Contrato |
| --- | --- |
| Unidades | `1 unidad = 1 mm`. STL no declara unidades: se interpreta en mm y nunca se reescala silenciosamente. |
| Base | Decisión del producto: diestra; `+Y` es arriba; el plano de trabajo es `XZ`. No hay conversión de ejes entre persistencia y worker; el fixture lo verifica en ambos runtimes. |
| Precisión | Persistencia JSON y worker usan números IEEE-754 binarios de 64 bits. Se rechazan `NaN`, infinitos y `-0` se normaliza a `0`. |
| Matriz | Afín 4×4; última fila matemática `[0, 0, 0, 1]`. Array JSON column-major: índices de traslación `12,13,14`. |
| Composición | Contrato del adaptador: para una jerarquía, `M_world = M_parent · M_local`. El snapshot de export guarda `M_world`, no obliga al worker a reconstruir la jerarquía. |
| Aplicación Python | Contrato del worker: reconstruye `M = np.asarray(values, dtype=np.float64).reshape((4, 4), order="F")`; aplica `M @ p_homogeneous` a un vector columna y `points_homogeneous @ M.T` a un array de filas. El fixture demuestra esta equivalencia antes de integrar Trimesh. |
| Rotación | La UI puede mostrar Euler en radianes, orden `XYZ`, pero persiste quaternion normalizado `[x,y,z,w]` solo como dato editable. Euler nunca cruza la frontera del worker. |
| Autoridad | La matriz es la autoridad del procesamiento. `translation`, `quaternion` y `scale` se guardan para edición y deben recomponer la misma matriz antes de aceptar el payload. |
| Escala | MVP: tres componentes finitos y estrictamente positivos. Se admite escala no uniforme; reflexión, escala cero y shear se rechazan. |
| Pivote | Es el origen local del STL original, inmutable. No se recentra al importar. Pivotes personalizados quedan fuera del MVP. |

### Frontera JSONB

Antes de persistir `geometry_jobs.payload`, Spring convierte cada número de matriz a
binary64, rechaza valores no finitos y normaliza `-0` a `0`; después emite su decimal de
round-trip. PostgreSQL `jsonb` conserva ese decimal como `numeric`, no la representación
binaria ni necesariamente su texto. El worker lee el valor como decimal, lo convierte una
sola vez a `np.float64`, vuelve a rechazar no finitos y aplica la misma normalización.

Un fixture productor/consumidor atraviesa PostgreSQL real y exige igualdad bit a bit de los
binary64 normalizados. Incluye `-0`, el subnormal positivo mínimo, el máximo finito y una
`matrixColumnMajor` completa; demuestra la conversión, no que JSONB sea binary64.

Ejemplo de identidad con traslación `(10, 20, 30)`:

```json
{
  "transformVersion": 1,
  "matrixColumnMajor": [
    1, 0, 0, 0,
    0, 1, 0, 0,
    0, 0, 1, 0,
    10, 20, 30, 1
  ],
  "translationMm": [10, 20, 30],
  "quaternionXyzw": [0, 0, 0, 1],
  "scale": [1, 1, 1]
}
```

## Tolerancias y rechazo

- Matriz recompuesta frente a matriz persistida: diferencia absoluta máxima `1e-10` por
  elemento.
- Fixture cruzado sobre vértices transformados: error euclídeo máximo
  `max(1e-6 mm, diagonal_del_asset × 1e-12)`.
- Quaternion: norma a distancia máxima `1e-10` de `1`; se canonicaliza el signo haciendo
  `w >= 0` (si `w == 0`, el primer componente no nulo es positivo).
- Determinante de la parte lineal: finito y `> 1e-12`. Esta cota protege contra matrices
  numéricamente singulares; no expresa una escala física universal.

Una infracción rechaza el guardado o el export con un código diagnóstico; nunca se corrige
una matriz de forma implícita.

## Fixtures obligatorios antes del Combined Export

Un fixture compartido, versionado fuera de las implementaciones, contiene vértices y casos:

1. identidad;
2. traslación fraccionaria;
3. rotación de 90° en cada eje;
4. quaternion compuesto sin ángulos rectos;
5. escala uniforme y no uniforme positiva;
6. jerarquía de tres niveles convertida a matriz mundial;
7. coordenadas grandes y pequeñas dentro de los límites admitidos;
8. casos inválidos: `NaN`, infinito, shear, reflexión y matriz singular.

TypeScript y Python consumen el mismo fixture. Una actualización de Three.js, NumPy o
Trimesh no se acepta si cambia los resultados fuera de tolerancia.

El fixture debe comparar explícitamente: el contenido y orden leído de `Matrix4.elements`,
la composición padre/local, la base Y-up elegida por el producto, el `reshape(order="F")` y
ambas formas de multiplicación Python. No se usa `Matrix4.toArray()` como autoridad mientras
su semántica exacta no quede cubierta por una prueba o por documentación de la versión fijada.

## Consecuencias

- Y-up evita conversiones ocultas entre la escena Three.js y el worker, pero el plano de
  impresión del editor es `XZ`, no el `XY` habitual de algunos programas CAD/slicers.
- La matriz autoritativa elimina ambigüedad de orden Euler y de composición.
- Prohibir shear y reflexiones permite una descomposición TRS estable en el MVP.

## Valores configurables y cuestiones aplazadas

- **Configurable:** límites de coordenadas y escala se fijan en ADR-0006 y se calibran con el
  corpus real.
- **Aplazado:** pivote personalizado, mirror/reflexión, shear y conversión explícita a una
  convención Z-up durante export. Requieren versión nueva del contrato.

## Fuentes

- [Three.js Matrix4: almacenamiento column-major](https://threejs.org/docs/pages/Matrix4.html)
- [Three.js Object3D: matrices, quaternion, Euler y Y-up por defecto](https://threejs.org/docs/pages/Object3D.html)
- [Trimesh transformations: vectores columna y `float64`](https://trimesh.org/trimesh.transformations.html)

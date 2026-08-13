# Geometry Worker

Worker Python independiente para procesamiento geométrico asíncrono.

Responsabilidades previstas:

- reclamar jobs desde PostgreSQL;
- inspeccionar STL y producir metadata;
- generar `preview.glb`;
- aplicar transformaciones reproducibles desde la escena;
- ejecutar boolean union mediante Manifold3D;
- validar resultados;
- exportar STL y otros artefactos derivados;
- actualizar estado/resultado del job.

El worker no autentica usuarios, no expone una API HTTP en el MVP y no mantiene locks de base de datos mientras procesa geometría.

## Quick path

```powershell
uv sync --locked
uv run ruff check .
uv run pytest -q
```

uv instala CPython 3.14 según `pyproject.toml` y resuelve exclusivamente `uv.lock`. Los tests
consumen la misma matriz que Three.js y fijan el surface del enum `manifold3d.Error` exigido
por ADR-0006.

El proceso actual es un lifecycle mínimo sin polling. Claim, lease, fencing y procesamiento
STL se implementarán juntos cuando exista el schema de jobs; introducirlos parcialmente
daría una falsa garantía de concurrencia.

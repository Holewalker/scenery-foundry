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

> El entorno Python y las versiones de Trimesh/Manifold3D se fijarán tras verificar las releases estables y completar el spike técnico.

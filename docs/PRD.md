# PRD — Editor web 3D de escenarios modulares

> Documento fuente inicial del proyecto. Estado: Draft v1.

El producto es una aplicación web para importar piezas STL, mantener un catálogo privado, componer escenarios tridimensionales y preparar geometría resultante para impresión 3D.

## Objetivo

Reducir la fricción entre colección de STL, diseño visual, composición del escenario, preparación geométrica, slicer externo e impresión 3D.

## Alcance MVP

- Registro e inicio de sesión con email/contraseña y cookie HttpOnly.
- Subida y procesamiento asíncrono de STL.
- Generación de `preview.glb` y metadata geométrica.
- Catálogo privado con búsqueda/filtros básicos.
- Editor 3D con R3F/Three.js, cámara orbital, grid, snap y transformaciones.
- Undo/redo durante la sesión.
- Niveles y grupos de impresión.
- Persistencia de escenas como referencias a assets + transformaciones.
- Pieces Export: ZIP de STL únicos + `manifest.json` con cantidades.
- Combined Export: aplicar transformaciones, unión booleana, validación y exportación STL.
- Jobs geométricos persistidos en PostgreSQL.
- Aislamiento estricto entre usuarios y descargas autorizadas desde Spring.

## Principios

1. El editor es el producto; no es un simple visor STL.
2. Una escena referencia assets; no duplica geometría innecesariamente.
3. `original.stl`, `preview.glb` y metadata son representaciones distintas.
4. La preparación de impresión no incluye slicing ni G-code.
5. Todo recurso privado tiene propietario y debe quedar scoped al usuario autenticado.
6. La primera arquitectura productiva debe caber en un único VPS con Docker Compose.

## Estados principales

Assets:

```text
UPLOADED
PROCESSING
READY
FAILED
```

Jobs:

```text
PENDING
RUNNING
COMPLETED
FAILED
```

## Modelo conceptual

```text
User
 ├── Asset *
 └── Project *
       ├── Level *
       ├── SceneObject * -> Asset
       ├── PrintGroup *
       └── Export * -> GeometryJob
```

Tablas iniciales sugeridas:

```text
users
assets
projects
levels
scene_objects
print_groups
exports
geometry_jobs
```

## Seguridad

- Nunca confiar en un `userId` enviado por el cliente.
- UUID no sustituye autorización.
- Los recursos privados de otro usuario deben resolverse como no accesibles, preferentemente `404`.
- `/data` no es público.
- Los nombres de fichero del usuario no se usan directamente como paths.
- CSRF debe configurarse correctamente para auth basada en sesión.

## Fases

### Fase 0 — Spike técnico

1. cargar STL;
2. convertir a GLB;
3. visualizar GLB en R3F;
4. crear dos instancias;
5. guardar transformaciones;
6. reproducir transformaciones en Python;
7. boolean union con Manifold;
8. exportar STL;
9. abrir STL final en un slicer real.

### Fase 1 — Editor mínimo

Catálogo local, Canvas, insertar, mover, rotar, snap, eliminar y guardar/cargar.

### Fase 2 — Assets

Upload, procesamiento, preview, metadata y catálogo persistente.

### Fase 3 — Preparación de impresión

Pieces Export, Print Groups, Combined Export y validación.

### Fase 4 — Producto

Auth, ownership, autosave, UX, backups y despliegue VPS.

### Fase 5 — Mejoras

Multiselección, mirror, volumen de impresora, reparación avanzada, thumbnails, instancing y optimizaciones.

## Criterio de aceptación principal

El MVP debe permitir a un usuario subir STL, componer una escena con múltiples instancias y niveles, guardar/reabrir, crear un grupo de impresión, generar Pieces Export y Combined Export válidos, mientras un segundo usuario no puede ver, procesar ni descargar sus recursos.

## Reglas para desarrollo

- No ampliar scope sin documentarlo.
- Consultar documentación actual antes de usar APIs sensibles a versión.
- No introducir Redis, Kafka, RabbitMQ, Kubernetes, object storage externo ni microservicios sin necesidad demostrada.
- La geometría pesada nunca se ejecuta dentro del request HTTP.
- Spring no implementa algoritmos de mesh; Python no implementa auth/dominio de usuarios.
- Los STL originales son inmutables; previews y exports son derivados.
- Nunca marcar un Combined Export como válido sin validación final.

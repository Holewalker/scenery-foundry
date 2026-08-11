# Arquitectura — Editor web 3D de escenarios modulares

**Estado:** Draft v1  
**Estilo:** modular monolith + geometry worker  
**Despliegue objetivo:** un único VPS con Docker Compose.

## Vista general

```text
INTERNET
   |
 HTTPS
   v
 Caddy
  |  \
SPA   /api/*
 |      v
React  Spring Boot
          | \
          |  \__ /data/assets + /data/exports
          v
      PostgreSQL
          |
     geometry_jobs
          v
   Python Worker
   Trimesh + Manifold3D
          |
          v
        /data
```

## Responsabilidades

### Frontend

- catálogo y editor;
- scene graph en memoria;
- grid snapping, selección, transform controls y undo/redo;
- comunicación REST y consulta de estados de jobs.

No autoriza recursos ni ejecuta geometría autoritativa.

### Spring Boot

Backend de producto: autenticación, autorización, usuarios, assets, proyectos, escenas, niveles, grupos, exports, jobs, descargas, persistencia y API.

Organización inicial por feature:

```text
com.product
├── auth/
├── user/
├── asset/
├── project/
├── scene/
├── printgroup/
├── export/
├── geometryjob/
├── storage/
└── common/
```

### Geometry Worker

Proceso Python sin API HTTP en el MVP. Reclama jobs, carga STL, inspecciona meshes, genera previews, aplica matrices, ejecuta booleanos, valida resultados y escribe artefactos.

## Frontera Java ↔ Python

```text
PostgreSQL + filesystem compartido
```

Spring crea jobs. Python los reclama y actualiza. No se introduce HTTP servicio-a-servicio inicialmente.

## Persistencia y storage

PostgreSQL almacena dominio, metadata, scene graph y estado de jobs. Los blobs 3D viven en filesystem:

```text
/data
├── assets/{asset_uuid}/
│   ├── original.stl
│   ├── preview.glb
│   └── thumbnail.webp
├── exports/{export_uuid}/
└── tmp/
```

`/data` no es público. Spring valida ownership antes de cualquier descarga.

## Ownership

Los aggregate roots privados incluyen propietario explícito. Los recursos dependientes derivan ownership del agregado.

Una consulta privada debe quedar scoped desde el principio:

```text
WHERE id = :resourceId
AND owner_id = :currentUserId
```

## Unidades y transformaciones

El dominio usa milímetros. La representación persistida debe reproducirse exactamente tanto en Three.js como en Python/Trimesh.

Antes del Combined Export debe existir un fixture que demuestre equivalencia de transformaciones entre frontend y worker.

## Jobs

La cola ligera reside en PostgreSQL. El claim usa una transacción corta con `FOR UPDATE SKIP LOCKED`; el procesamiento ocurre fuera de transacción. Los jobs usan lease, reintentos limitados y recuperación de trabajos abandonados.

```text
BEGIN
  claim + RUNNING + lease
COMMIT

process geometry

BEGIN
  COMPLETED / FAILED
COMMIT
```

## Combined Export

Al solicitar un export, Spring valida ownership y captura un snapshot estable de asset IDs, checksum/version del STL original, transforms y configuración. El worker ejecuta:

```text
load
 -> validate input
 -> normalize supported issues
 -> apply transforms
 -> boolean/combine
 -> validate result
 -> export
```

Manifold3D es el primer motor booleano. Blender headless queda como fallback potencial, no dependencia obligatoria.

## Docker Compose

Servicios iniciales:

```text
caddy
backend
geometry-worker
postgres
```

La SPA puede compilarse y ser servida estáticamente por Caddy. Solo 80/443 se publican externamente.

## Testing

- backend: unit + integración con PostgreSQL real/Testcontainers + autorización;
- geometry: fixtures STL para load, transforms, union, validation y export;
- frontend: stores/reducers, serialización y transformaciones;
- seguridad: siempre dos usuarios y pruebas de acceso cruzado.

## Invariantes arquitectónicos

1. El editor nunca accede a STL privados sin autorización backend.
2. `/data` no es público.
3. El user ID efectivo procede de Spring Security.
4. Geometry Worker no autoriza usuarios.
5. Spring no procesa booleanos 3D.
6. STL original es inmutable.
7. GLB preview y exports son derivados.
8. Un job no mantiene locks durante procesamiento geométrico.
9. Jobs abandonados son recuperables.
10. Transformaciones reproducibles entre Three.js y Python.
11. La aplicación debe seguir arrancando mediante Docker Compose.
12. No añadir infraestructura distribuida sin una métrica que la justifique.

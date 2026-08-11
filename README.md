# scenery-foundry

Editor web 3D para componer escenarios modulares y preparar geometría STL para impresión 3D.

## Estado

Proyecto en fase inicial / spike técnico.

El producto se centra en:

- catálogo privado de assets STL;
- previews web ligeras en GLB;
- editor 3D con React Three Fiber;
- persistencia de escenas por referencias + transformaciones;
- grupos de impresión;
- exportación de piezas y combinación geométrica;
- aislamiento estricto entre usuarios.

No pretende sustituir a un slicer ni generar G-code.

## Arquitectura objetivo

```text
Browser
  React + TypeScript + Vite + R3F
              |
              | HTTPS / REST
              v
        Spring Boot
              |
       PostgreSQL + /data
              |
              v
     Python geometry worker
     Trimesh + Manifold3D
```

Despliegue inicial: un único VPS mediante Docker Compose y Caddy.

## Estructura del repositorio

```text
backend/          Spring Boot: API, auth, dominio, persistencia y jobs
frontend/         React/Vite: catálogo y editor 3D
geometry-worker/  Python: procesamiento geométrico asíncrono
docs/             PRD, arquitectura, stack y ADRs
```

## Documentación

- [`docs/PRD.md`](docs/PRD.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/STACK.md`](docs/STACK.md)
- [`docs/adr/`](docs/adr/README.md) — decisiones de arquitectura aceptadas

## Principios no negociables

1. Los STL originales son inmutables.
2. Las previews y exports son artefactos derivados y regenerables.
3. La geometría pesada nunca se ejecuta dentro del request HTTP.
4. El frontend no es una frontera de seguridad.
5. Todo recurso privado se consulta dentro del scope del usuario autenticado.
6. `/data` nunca se expone públicamente.
7. Los jobs geométricos usan PostgreSQL con claims cortos, `SKIP LOCKED` y lease.
8. La aplicación debe seguir pudiendo ejecutarse con Docker Compose.
9. No se añade infraestructura distribuida sin una necesidad demostrada.

## Primer objetivo técnico

Completar el spike de extremo a extremo:

1. cargar STL;
2. convertir a GLB;
3. visualizarlo en R3F;
4. crear dos instancias y persistir transformaciones;
5. reproducir exactamente esas transformaciones en Python;
6. ejecutar una unión con Manifold3D;
7. exportar STL y abrirlo en un slicer real.

La baseline tecnológica, sus rangos aceptados y sus fuentes oficiales están documentados en
[`docs/STACK.md`](docs/STACK.md); los lockfiles fijan la resolución exacta de cada scaffold.

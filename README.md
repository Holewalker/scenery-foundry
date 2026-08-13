# scenery-foundry

Editor web 3D para componer escenarios modulares y preparar geometría STL para impresión 3D.

## Estado

Bootstrap ejecutable disponible; el dominio y el spike geométrico siguen en desarrollo.

## Quick path

Requisitos de la baseline: JDK 25, Node.js 24.19, npm 11.17, Python 3.14 gestionado por
uv 0.12.3 y Docker Compose. Los manifiestos conservan esas versiones; los lockfiles de
Node y Python fijan la resolución de sus scaffolds.

```powershell
Copy-Item .env.example .env
./scripts/check.ps1
docker compose up --build --wait
```

La SPA queda en `http://localhost:8081`, el backend en `http://localhost:8080` y su health
check en `/actuator/health`. `docker compose down` conserva deliberadamente los volúmenes del proyecto manual; la
prueba completa usa un proyecto efímero y elimina solo sus recursos e imágenes locales.

En POSIX, usa `./scripts/check.sh`. Ambos comandos instalan exclusivamente desde los
lockfiles y verifican backend, frontend, worker y la estructura Compose.

El modo predeterminado es prueba completa: exige JDK 25, Node 24, Python 3.14 gestionado
por uv y un daemon Docker; además rechaza cualquier test PostgreSQL omitido y levanta el
stack hasta que esté healthy. Para validar solo la estructura sin afirmar evidencia runtime,
usa `./scripts/check.ps1 -Mode quick` o `./scripts/check.sh quick`.

Este Compose es un harness local: publica puertos solo en loopback y usa credenciales de
desarrollo. Caddy, HTTPS, cookies `Secure` y digests de producción se incorporarán juntos
en el work unit de despliegue; reutilizar esta configuración directamente en Internet no
es seguro.

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
[`docs/STACK.md`](docs/STACK.md); los lockfiles de Node y Python fijan la resolución de
sus respectivos scaffolds.

## Alcance del bootstrap

Ya existe evidencia ejecutable para el health check, el endpoint CSRF basado en sesión, la
migración Flyway, el shell React, el enum de Manifold3D y el fixture compartido de matrices.
El fixture JCS fija bytes y SHA-256, pero la canonicalización productiva Java/Python queda
para el siguiente work unit. También quedan aplazados auth real, ownership, el schema y
fencing de jobs, el polling PostgreSQL del worker, el pipeline STL y Caddy/HTTPS. No se
simulan esas garantías antes de tener su dominio y sus pruebas de concurrencia.

Playwright permanece como herramienta E2E elegida, pero se incorporará al lockfile con el
primer journey real; instalarlo ahora sin una frontera verificable produciría una prueba
vacía, no evidencia.

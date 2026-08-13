# Stack técnico — Editor web 3D de escenarios modulares

**Estado:** Baseline aceptada

**Última verificación:** 2026-08-11

**Principio:** pocas piezas, versiones reproducibles y responsabilidades claras.

## Baseline ejecutable

Esta es la combinación que debe usar el primer scaffold. Las versiones de aplicación se
declaran como rangos compatibles y los lockfiles resuelven versiones exactas. Las imágenes
de producción se fijan por versión completa y, al desplegar, por digest.

| Área | Baseline | Herramienta y bloqueo | Verificación |
| --- | --- | --- | --- |
| Backend | Eclipse Temurin JDK **25 LTS** + Spring Boot **4.1.0** | Maven Wrapper **3.9.16**; BOM de Spring Boot; `mvnw` | JUnit **6.0.3** mediante `spring-boot-starter-test`, Spring Security Test y Testcontainers **2.0.5** con PostgreSQL real |
| Frontend | Node.js **24.19.0 LTS**, React/React DOM **19.2.8**, TypeScript **7.0.2**, Vite **8.2.1** | npm **11.17.0** y `package-lock.json` v3; `npm ci` | Vitest **4.1.10**, Testing Library React **16.3.2** y Playwright **1.62.1** |
| 3D web | Three.js **0.185.1**, React Three Fiber **9.7.0**, Drei **10.7.8**, Zustand **5.0.14** | mismo `package-lock.json` del frontend | fixtures de serialización y transformación en Vitest; journeys críticos en Playwright |
| Worker | CPython **3.14.x**, Trimesh **5.0.0**, Manifold3D **3.5.2**, NumPy **2.5.2** | uv **0.12.3**, `pyproject.toml` y `uv.lock`; `uv sync --locked` | pytest **9.1.1**; Ruff **0.16.2** para formato y lint |
| Datos | PostgreSQL **18.4** | imagen `postgres:18.4` inicialmente; migraciones exclusivas de Flyway | Testcontainers con el mismo major; pruebas de locking, JSONB y autorización |
| Contenedores | Docker Engine **29.7.2** + Docker Compose **5.4.0** | Compose Specification; imágenes con tag completo y digest en despliegue | `docker compose config` y smoke test del stack cuando exista el scaffold |
| Entrada HTTPS | Caddy **2.11.4** | imagen oficial `caddy:2.11.4-alpine`, fijada por digest en despliegue | `caddy validate` y smoke test HTTPS cuando exista configuración |

### Por qué esta combinación

- **Java 25 + Spring Boot 4.1:** es un proyecto nuevo, por lo que se adopta la línea estable
  actual sin asumir deuda de migración a Jakarta Servlet 6.1. Spring Boot 4.1 declara
  compatibilidad con Java 17–26; Temurin identifica Java 25 como LTS.
- **Node 24 LTS, no Node 26 Current:** Vite admite ambas versiones por requisito de motor,
  pero Node recomienda producción sobre una línea LTS. Se evita una actualización mayor
  inmediata cuando Node 26 cambie de estado.
- **React 19 + R3F 9:** el peer contract publicado por R3F 9.7 admite React `>=19 <19.3` y
  Three.js `>=0.156`; Drei 10.7 admite R3F 9 y Three.js `>=0.159`.
- **Python 3.14:** es la estable actual y existen wheels oficiales de Manifold3D 3.5.2 para
  CPython 3.14 en Linux x86-64 y arm64. El despliegue no compila Manifold3D desde fuente.
- **PostgreSQL 18:** es el major estable actual, con soporte comunitario previsto hasta 2030.
  Se priorizan sus minor releases porque PostgreSQL recomienda mantener el minor vigente.
- **Docker Compose, no un orquestador adicional:** cubre el único VPS previsto y conserva
  una ruta operativa sencilla.

## Dependencias por componente

### Backend

Spring MVC, no WebFlux. Spring Boot administra las versiones transitivas; no se duplican
versiones individuales en el POM salvo una incompatibilidad demostrada.

```text
spring-boot-starter-web
spring-boot-starter-security
spring-boot-starter-validation
spring-boot-starter-data-jpa
spring-boot-starter-actuator
postgresql JDBC driver
spring-boot-starter-flyway
flyway-database-postgresql
```

Testing:

```text
spring-boot-starter-test
spring-security-test
Testcontainers PostgreSQL
```

Maven Wrapper es la única entrada soportada (`./mvnw` o `mvnw.cmd`). Flyway es el único
actor que modifica el esquema. No se usa `ddl-auto` fuera de validación.

### Autenticación

Spring Security con `SecurityContext` persistido en `HttpSession` y CSRF habilitado mediante
`HttpSessionCsrfTokenRepository`. La cookie de sesión usa `HttpOnly`, `Secure` en producción
y `SameSite=Lax` como valor inicial, configurados en Spring Boot/contenedor servlet: Spring
Security no crea esa cookie ni controla directamente `SameSite`. JWT no es necesario para
el MVP. El orden y los límites exactos se fijan en
[ADR-0003](adr/0003-ownership-y-orden-de-seguridad.md).

### Frontend

React + TypeScript + Vite construyen una SPA. Three.js es el motor 3D; React Three Fiber
integra el scene graph; Drei aporta helpers cuando evita código propio; Zustand conserva
estado transitorio del editor. La fuente autoritativa persistente sigue siendo Spring +
PostgreSQL.

`package.json` expresa rangos compatibles dentro del major/minor aceptado; el
`package-lock.json` se versiona y CI instala exclusivamente con `npm ci`. No se usa el tag
`latest` durante build o despliegue.

Playwright sigue siendo la herramienta E2E aceptada, pero no se instala en el bootstrap:
todavía no existe un journey ni una frontera UI completa que pueda probar sin una aserción
vacía. Se añadirá al lockfile junto con el primer journey ejecutable; Vitest cubre mientras
tanto el shell y los contratos de transformación.

### Geometry Worker

Proceso Python independiente, sin API HTTP. Trimesh carga, inspecciona, transforma y
exporta; Manifold3D es el motor booleano primario; NumPy proporciona álgebra lineal.

`uv.lock` se versiona y CI usa `uv sync --locked`. Trimesh advierte que su API no garantiza
estabilidad completa: por eso queda fijado exactamente en el lockfile y se actualiza con
fixtures geométricos. Blender queda fuera hasta que un spike pruebe una necesidad concreta.

## Formatos y storage

- STL: original imprimible y export final; sus números se interpretan en milímetros.
- GLB: preview web derivado y potencialmente simplificado; nunca reemplaza al STL.
- Matrices: contrato versionado definido en [ADR-0001](adr/0001-unidades-coordenadas-y-transformaciones.md).

El storage MVP usa filesystem local compartido:

```text
/data/assets/{asset_uuid}/
/data/exports/{export_id}/attempts/{claim_token}
/data/tmp/{job_id}/{claim_token}
```

Spring expone descargas autorizadas; Caddy no publica `/data`. Una abstracción pequeña
`StorageService` evita filtrar paths físicos al dominio. La frontera con el worker se define
en [ADR-0002](adr/0002-frontera-spring-worker.md).

## Política de actualización

1. Los lockfiles y digests son parte del cambio y se revisan como código.
2. Parches de seguridad: actualización prioritaria, ejecutando toda la evidencia aplicable.
3. Parches ordinarios: lote mensual. Minors: lote trimestral. Majors: ADR o actualización de
   este documento, spike de compatibilidad y plan de rollback.
4. Java, Node, Python y PostgreSQL solo avanzan a una línea soportada. Nunca se incorpora
   una RC/beta/canary a producción sin una decisión explícita.
5. Cada actualización de Three.js, R3F, Trimesh o Manifold3D debe ejecutar los fixtures
   cruzados de matrices y el corpus geométrico antes de aceptarse.
6. Se vuelve a contrastar esta tabla antes de crear el scaffold y después cada tres meses.

Los números de patch reflejan lo comprobado el 2026-08-11; no son una promesa de permanecer
en ese patch. El major/minor aceptado y los contratos de los ADR sí son decisiones firmes
hasta que una nueva decisión los reemplace.

## Fuentes primarias consultadas

- [Spring Boot 4.1 — requisitos de sistema](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Boot 4.1 — versiones gestionadas de JUnit y Testcontainers](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)
- [Spring Security 7.1 — sesión, CSRF y límites de `SameSite`](https://docs.spring.io/spring-security/reference/7.1/)
- [Eclipse Temurin — roadmap de soporte](https://adoptium.net/support/)
- [Apache Maven — descargas estables](https://maven.apache.org/download.cgi)
- [Node.js 24.19.0 — versión LTS y npm incluido](https://nodejs.org/en/download/archive/v24.19.0)
- [Node.js — líneas de release y estado LTS](https://nodejs.org/en/about/previous-releases)
- [Vite — requisitos de Node.js](https://vite.dev/guide/)
- Metadata npm versionada: [React 19.2.8](https://registry.npmjs.org/react/19.2.8), [Vite 8.2.1](https://registry.npmjs.org/vite/8.2.1), [Three.js 0.185.1](https://registry.npmjs.org/three/0.185.1), [R3F 9.7.0](https://registry.npmjs.org/@react-three%2Ffiber/9.7.0), [Drei 10.7.8](https://registry.npmjs.org/@react-three%2Fdrei/10.7.8), [Zustand 5.0.14](https://registry.npmjs.org/zustand/5.0.14), [Vitest 4.1.10](https://registry.npmjs.org/vitest/4.1.10) y [Playwright 1.62.1](https://registry.npmjs.org/playwright/1.62.1)
- [Python 3.14 — novedades y estado estable](https://docs.python.org/3/whatsnew/3.14.html)
- [uv — proyectos y lockfile](https://docs.astral.sh/uv/guides/projects/)
- Metadata PyPI versionada: [Trimesh 5.0.0](https://pypi.org/pypi/trimesh/5.0.0/json), [Manifold3D 3.5.2](https://pypi.org/pypi/manifold3d/3.5.2/json), [NumPy 2.5.2](https://pypi.org/pypi/numpy/2.5.2/json) y [pytest 9.1.1](https://pypi.org/pypi/pytest/9.1.1/json)
- [PostgreSQL — política de versiones](https://www.postgresql.org/support/versioning/)
- [Docker Engine 29 — release notes](https://docs.docker.com/engine/release-notes/29/)
- [Docker Compose 5.4.0 — release oficial](https://github.com/docker/compose/releases/tag/v5.4.0)
- [Caddy 2.11.4 — release oficial](https://github.com/caddyserver/caddy/releases/tag/v2.11.4)

### Límites de esta auditoría documental

- El BOM oficial de Spring Boot 4.1.0 gestiona Spring Security **7.1.0**; esa documentación es
  autoritativa. La referencia 7.0 que expuso Context7 fue solo evidencia preliminar y no
  sustenta el contrato final.
- Context7 expuso documentación oficial de Manifold desde la rama principal, no un corpus
  versionado para Manifold3D 3.5.2. El scaffold debe fijar 3.5.2 en su lockfile y probar su
  enum `Error`, construcción de meshes y booleanos contra el corpus antes de aceptar el contrato.
- Este baseline precede a los scaffolds, por lo que aún no existen lockfiles ni es correcto
  inventar sus digests. Al crear cada scaffold, `package-lock.json`/`uv.lock` deben registrar
  las integridades o hashes resueltos y el cambio debe contrastarlos con la metadata
  versionada anterior.

## Dependencias rechazadas por defecto en el MVP

```text
Redis, Celery, RabbitMQ, Kafka, Kubernetes, MinIO, Elasticsearch,
Next.js server runtime, FastAPI, Django, Spring WebFlux, Spring Cloud,
Temporal y Airflow
```

Toda nueva pieza debe justificar qué problema actual resuelve, por qué el stack existente
no lo resuelve, cuál es su coste operativo y si sigue siendo razonable en un único VPS.

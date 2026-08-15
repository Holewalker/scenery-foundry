# Registro de entrega: implement-minimum-3d-editor

Este cambio SDD entrega el editor 3D autenticado mínimo: un catálogo de assets acotado por
propietario, una API de guardado/recarga atómica de la escena completa, y un editor React
Three Fiber servido same-origin a través de Nginx. Se entregó como una cadena de PRs en draft
(tracker `feature/tracker`, base `main`) para que cada PR se mantuviera dentro del presupuesto
de revisión. Este registro captura la evidencia de verificación final de toda la cadena: la
suite completa `./scripts/check.ps1` y un journey de entrega de extremo a extremo ejecutado de
forma independiente y manual.

## Camino rápido

1. `./scripts/check.ps1` — modo completo: toolchains, tests focalizados, frontera con
   PostgreSQL, construcción de imágenes, y salud del stack. Ver
   [Suite de verificación](#suite-de-verificación-scriptscheckps1) abajo para la ejecución
   exacta y el resultado.
2. Journey de entrega manual — login, obtención del STL, inserción/transformación de un objeto,
   guardado, recarga, confirmación de persistencia, todo a través del origen del frontend con
   el stack completo levantado. Ver
   [Journey de entrega de extremo a extremo](#journey-de-entrega-de-extremo-a-extremo) abajo
   para los comandos exactos y la evidencia.

## Suite de verificación: `./scripts/check.ps1`

| Campo | Valor |
|-------|-------|
| Comando | `./scripts/check.ps1` (modo completo por defecto — sin `-Mode quick`) |
| Código de salida | `0` |
| Tests de backend | `Tests run: 39, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS` |
| Cobertura | `JcsFixtureTest`, `CsrfControllerTest`, `ConcurrentCaptureIntegrationTest`, `ExportControllerTest`, `JdbcCaptureProjectionServiceTest`, `JdbcExportRepositoryTest`, `SnapshotV1WriterTest`, `AuthControllerTest`, `AuthenticatedUserTest`, `IdentityMigrationIntegrationTest`, `PlatformMigrationIntegrationTest`, `OwnedSceneMigrationIntegrationTest`, `OwnedSceneServiceTest`, `ProjectControllerTest`, `SceneryFoundryApplicationTest` |
| Construcción de imágenes | `backend`, `frontend`, `geometry-worker` — todas construidas |
| Salud del stack | `postgres`, `backend`, `geometry-worker`, `frontend` reportaron `Healthy`, luego el propio script los detuvo |
| Línea final | `Full verification passed: toolchains, tests, PostgreSQL boundary, images, and stack health.` |

El script provisiona su propio proyecto Compose aislado (sufijo aleatorio, por ejemplo
`scenery-foundry-check-<pid>-<hash>`), así que no interfiere con una sesión propia de
`docker compose up` de un desarrollador.

## Journey de entrega de extremo a extremo

Ejecutado de forma independiente de `check.ps1`, contra el proyecto real de `compose.yml`,
para probar el camino manual exacto que una persona ejercitaría en el navegador: login,
obtención de los bytes STL de un asset del catálogo, inserción y transformación de un objeto
en la escena, guardado, recarga, y confirmación de que persistió — todo a través del origen
del frontend (`http://localhost:8081`), que Nginx redirige al backend bajo `/api` en el mismo
origen (sin CORS de por medio).

### Preparación

```powershell
docker compose up -d --wait
docker compose ps
```

Resultado: los cuatro servicios (`postgres`, `backend`, `geometry-worker`, `frontend`)
reportaron `Up ... (healthy)`.

Se provisionaron un usuario de desarrollo, un proyecto propio, y un asset sembrado
(`data/seed/triangle.stl`, un fixture local desechable — ver
[`docs/local-editor-seeding.md`](../local-editor-seeding.md)) mediante `psql` y
`scripts/seed-local-editor.ps1`, siguiendo el mismo patrón que describe ese documento.

### Pasos del journey y evidencia

Todas las peticiones siguientes pasaron por `http://localhost:8081` (el origen
frontend/Nginx), usando un único cookie jar de `curl` para llevar la sesión entre peticiones —
exactamente como lo haría un navegador.

| Paso | Petición | Resultado |
|------|---------|--------|
| 1. CSRF previo al login | `GET /api/csrf` | `200`, devolvió un token asociado a la sesión |
| 2. Login | `POST /api/auth/login` con `X-CSRF-TOKEN` del paso 1 | `HTTP/1.1 204`, sesión rotada (`Set-Cookie: JSESSIONID=...`) |
| 3. CSRF posterior al login | `GET /api/csrf` (fresco — el login rotó la sesión, invalidando el token previo) | `200`, token nuevo |
| 4. Confirmación de acceso al proyecto | `GET /api/projects/{id}` | `200`, `{"id":"b6e2473b-..."}`  |
| 5. Catálogo de assets | `GET /api/projects/{id}/assets` | `200`, `[{"id":"65f6c7ba-..."}]` — el asset sembrado |
| 6. Obtención de bytes STL | `GET /api/projects/{id}/assets/{assetId}/original` | `200`, 128 bytes; el `sha256sum` de los bytes descargados coincidió exactamente con `data/seed/triangle.stl` (`diff` no reportó diferencias) |
| 7. Escena antes de guardar | `GET /api/projects/{id}/scene` | `200`, `{"objects":[]}` |
| 8. Insertar + transformar, guardar | `PUT /api/projects/{id}/scene` con `X-CSRF-TOKEN` del paso 3, un objeto trasladado a `(10, 20, 30)` mm | `200`, devolvió el objeto exacto con la traslación, cuaternión, escala y matriz column-major enviados |
| 9. Recarga | `GET /api/projects/{id}/scene` fresco | `200`, devolvió un objeto idéntico al del paso 8 — confirma que el guardado persistió y sobrevive a una recarga, no solo un eco dentro de la misma petición |
| 10. Salud del stack tras el journey | `docker compose ps` | Los cuatro servicios seguían `healthy` |

### Cierre

```powershell
docker compose down
```

Resultado: todos los contenedores y la red se eliminaron limpiamente, código de salida `0`.

## Checklist

- [x] `./scripts/check.ps1` pasó (código de salida `0`, modo completo, los 39 tests de backend
      en verde, los cuatro servicios saludables).
- [x] El login funcionó y rotó la sesión como se esperaba.
- [x] El token CSRF obtenido después del login (no antes) fue requerido y aceptado para la
      petición mutante `PUT /api/projects/{id}/scene`.
- [x] Los bytes STL obtenidos son idénticos byte a byte al fixture sembrado (`sha256sum` +
      `diff`).
- [x] Un objeto insertado/transformado se conserva exactamente a través del guardado y una
      recarga fresca.
- [x] El stack se mantuvo saludable durante todo el journey y se detuvo limpiamente después.

## Siguiente paso

Ver [`docs/local-editor-seeding.md`](../local-editor-seeding.md) para cómo provisionar los
fixtures locales usados arriba, y el [`README.md`](../../README.md) de nivel superior para el
arranque completo del stack.

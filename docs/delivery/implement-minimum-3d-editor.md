# Delivery record: implement-minimum-3d-editor

This SDD change delivers the minimum authenticated 3D editor: an owner-scoped asset catalog,
an atomic whole-scene save/reload API, and a React Three Fiber editor served same-origin
through Nginx. It shipped as a draft feature-branch chain (tracker `feature/tracker`, base
`main`) so each PR stayed inside the reviewer budget. This record captures the final
verification evidence for the whole chain: the full `./scripts/check.ps1` suite and an
independent, manually-run end-to-end delivery journey.

## Quick path

1. `./scripts/check.ps1` — full mode: toolchains, focused tests, PostgreSQL boundary, image
   builds, and stack health. See [Verification suite](#verification-suite-scriptscheckps1)
   below for the exact run and result.
2. Manual delivery journey — login, fetch STL, insert/transform an object, save, reload,
   confirm persistence, all through the frontend origin with the full stack running. See
   [End-to-end delivery journey](#end-to-end-delivery-journey) below for the exact commands
   and evidence.

## Verification suite: `./scripts/check.ps1`

| Field | Value |
|-------|-------|
| Command | `./scripts/check.ps1` (default full mode — no `-Mode quick`) |
| Exit code | `0` |
| Backend tests | `Tests run: 39, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS` |
| Coverage | `JcsFixtureTest`, `CsrfControllerTest`, `ConcurrentCaptureIntegrationTest`, `ExportControllerTest`, `JdbcCaptureProjectionServiceTest`, `JdbcExportRepositoryTest`, `SnapshotV1WriterTest`, `AuthControllerTest`, `AuthenticatedUserTest`, `IdentityMigrationIntegrationTest`, `PlatformMigrationIntegrationTest`, `OwnedSceneMigrationIntegrationTest`, `OwnedSceneServiceTest`, `ProjectControllerTest`, `SceneryFoundryApplicationTest` |
| Image builds | `backend`, `frontend`, `geometry-worker` — all built |
| Stack health | `postgres`, `backend`, `geometry-worker`, `frontend` all reported `Healthy`, then torn down by the script itself |
| Final line | `Full verification passed: toolchains, tests, PostgreSQL boundary, images, and stack health.` |

The script provisions its own isolated Compose project (random suffix, e.g.
`scenery-foundry-check-<pid>-<hash>`), so it does not interact with a developer's own
`docker compose up` session.

## End-to-end delivery journey

Run independently of `check.ps1`, against the real `compose.yml` project, to prove the exact
manual path a person exercises in the browser: login, fetch the STL bytes for a catalog asset,
insert and transform an object in the scene, save, reload, and confirm it persisted — all
through the frontend origin (`http://localhost:8081`), which Nginx proxies to the backend under
`/api` same-origin (no CORS involved).

### Setup

```powershell
docker compose up -d --wait
docker compose ps
```

Result: all four services (`postgres`, `backend`, `geometry-worker`, `frontend`) reported
`Up ... (healthy)`.

A dev user, an owned project, and one seeded asset (`data/seed/triangle.stl`, a disposable
local fixture — see [`docs/local-editor-seeding.md`](../local-editor-seeding.md)) were
provisioned via `psql` and `scripts/seed-local-editor.ps1`, following the same pattern that
doc describes.

### Journey steps and evidence

All requests below went through `http://localhost:8081` (the frontend/Nginx origin), using a
single `curl` cookie jar to carry the session across requests — exactly how a browser would.

| Step | Request | Result |
|------|---------|--------|
| 1. Pre-login CSRF | `GET /api/csrf` | `200`, returned a session-bound token |
| 2. Login | `POST /api/auth/login` with `X-CSRF-TOKEN` from step 1 | `HTTP/1.1 204`, session rotated (`Set-Cookie: JSESSIONID=...`) |
| 3. Post-login CSRF | `GET /api/csrf` (fresh — the login rotated the session, invalidating the pre-login token) | `200`, new token |
| 4. Confirm project access | `GET /api/projects/{id}` | `200`, `{"id":"b6e2473b-..."}`  |
| 5. Asset catalog | `GET /api/projects/{id}/assets` | `200`, `[{"id":"65f6c7ba-..."}]` — the seeded asset |
| 6. Fetch STL bytes | `GET /api/projects/{id}/assets/{assetId}/original` | `200`, 128 bytes; `sha256sum` of the downloaded bytes matched `data/seed/triangle.stl` exactly (`diff` reported no difference) |
| 7. Scene before save | `GET /api/projects/{id}/scene` | `200`, `{"objects":[]}` |
| 8. Insert + transform, save | `PUT /api/projects/{id}/scene` with `X-CSRF-TOKEN` from step 3, one object translated to `(10, 20, 30)` mm | `200`, echoed the object back with the exact translation, quaternion, scale, and column-major matrix sent |
| 9. Reload | Fresh `GET /api/projects/{id}/scene` | `200`, returned object identical to step 8 — confirms the save persisted and survives a reload, not just an in-request echo |
| 10. Stack health after the journey | `docker compose ps` | All four services still `healthy` |

### Teardown

```powershell
docker compose down
```

Result: all containers and the network removed cleanly, exit code `0`.

## Checklist

- [x] `./scripts/check.ps1` passed (exit `0`, full mode, all 39 backend tests green, all four
      services healthy).
- [x] Login succeeded and rotated the session as expected.
- [x] The CSRF token fetched after login (not before) was required and accepted for the
      mutating `PUT /api/projects/{id}/scene` request.
- [x] The fetched STL bytes are byte-identical to the seeded fixture (`sha256sum` + `diff`).
- [x] An inserted/transformed object round-trips exactly through save and a fresh reload.
- [x] The stack stayed healthy for the full journey and tore down cleanly afterward.

## Next step

See [`docs/local-editor-seeding.md`](../local-editor-seeding.md) for how to provision the local
fixtures used above, and the top-level [`README.md`](../../README.md) for full-stack startup.

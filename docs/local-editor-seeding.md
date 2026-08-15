# Local editor seeding (STL fixtures)

To exercise the 3D editor end to end on a developer machine, you need a logged-in user, a
project they own, and a `prepared_assets` row that points at a real `.stl` file. `data/seed/`
and `scripts/seed-local-editor.ps1` provide that wiring for local development only. **They are
disposable local fixtures, not an upload feature, not a manifest, and not object storage.**
Nothing under `data/` is served, backed up, or shipped; the directory is fully gitignored
(`/data/` and `data/` in `.gitignore`) and every path under it can be deleted and recreated at
any time without consequence.

## Quick path

1. Create a project-owning user and project directly in PostgreSQL (there is no self-service
   registration endpoint yet):

   ```powershell
   docker compose exec -T postgres psql -U scenery -d scenery_foundry -c @"
   insert into users(id, email, password_hash)
     values (gen_random_uuid(), 'dev@example.com', crypt('dev-password', gen_salt('bf', 12)))
     returning id;
   "@
   ```

   Use the returned `id` as `<user-id>` below, then insert an owned project with
   `insert into projects(id, owner_id) values (gen_random_uuid(), '<user-id>') returning id;`.

2. Drop a real `.stl` file under `data/seed/`, for example `data/seed/fixture.stl`. Never commit
   this file — `data/` is gitignored precisely so nobody has to remember that.

3. Run the seed script to register that file as a `prepared_assets` row scoped to the project:

   ```powershell
   ./scripts/seed-local-editor.ps1 `
     -RelativePath "seed/fixture.stl" `
     -UserId "<user-id>" `
     -ProjectId "<project-id>" `
     -AssetId (New-Guid)
   ```

4. Start the stack (`docker compose up -d --wait`), log in with the seeded credentials, and open
   the editor at `http://localhost:8081/?project=<project-id>`. The seeded asset appears in the
   catalog and can be inserted into the scene.

## Details

| Topic | Decision |
|-------|----------|
| What `data/seed/*.stl` is | A local-only source file the seed script reads to compute a SHA-256 checksum and register a `storage_key`. It plays the same role a real upload pipeline would play once one exists. |
| What it is NOT | Not an upload endpoint, not a persisted manifest, not any form of object storage. There is no code path that writes into `data/` from application traffic. |
| Path safety | `Resolve-SeedAssetPath` in `scripts/seed-local-editor.ps1` rejects absolute paths, non-`.stl` extensions, missing files, symlinks, and any path that escapes `data/` via traversal. |
| Ownership | The script verifies the given user exists and owns the given project before writing the `prepared_assets` row; it never creates users or projects itself. |
| Git hygiene | `data/` and `/data/` are both listed in `.gitignore`. No `.stl` binary or seed data is ever expected in a commit or a PR diff. |
| Container access | `compose.yml` bind-mounts `./data:/data` into `backend` and `geometry-worker`, so a file dropped under `data/seed/` on the host is visible to both services without a rebuild. |

## Checklist

- [ ] The `.stl` fixture lives under `data/seed/` and is not staged in Git.
- [ ] The seeding user exists and owns the project referenced by `-ProjectId`.
- [ ] `scripts/seed-local-editor.ps1` printed `Seeded asset <id> -> seed/<file> (<sha256>)`.
- [ ] The asset is visible in the editor's catalog after logging in with the seeded user.

## Next step

See the top-level [`README.md`](../README.md) Quick path for full-stack startup, and
`scripts/seed-local-editor-test.ps1` for the unit tests covering the script's path-safety and
ownership checks.

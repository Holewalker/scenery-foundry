# Runbook: Backup & Restore

Related: [ADR-0008 — Topología de despliegue y operación](../adr/0008-topologia-de-despliegue-y-operacion.md).

## What is backed up

The `backup` Compose sidecar runs nightly (default `03:00 UTC`, `BACKUP_HOUR_UTC`) and produces,
under `./backups` on the VPS host:

- `db.dump` — a `pg_dump -Fc` (custom format) dump of the whole database.
- `originals.tar.gz` — every **original** uploaded STL under `./data/assets/*/*.stl`. Derived
  artifacts (`preview.glb` next to each original, `./data/pieces/*.stl`,
  `./data/exports/*/snapshot.json`) are **never** included — they are reproducible from the
  originals (ADR-0002/ADR-0004), so backing them up would only multiply size without adding
  recovery capability (D5).
- `manifest.sha256` — sha256 checksums of both files above, so a truncated or corrupted backup
  is detectable before anyone tries to restore it.

Backups are published atomically: the job stages into `./backups/.staging/<timestamp>/` and only
`mv`s it into `./backups/daily/<timestamp>/` once every step above has succeeded. An interrupted
run never appears under `daily/`.

Retention (D6): the **7** most recent daily backups are kept; on Sundays a daily backup is also
hardlinked into `./backups/weekly/`, keeping the **4** most recent weekly backups. Older entries
are pruned automatically by the same job.

## Restoring

### Automated smoke test (proves recoverability, does not touch production)

```sh
docker compose exec backup bash /scripts/backup/restore-smoke.sh
```

This restores the newest `daily/` backup into a throwaway database (`restore_smoke_<pid>`,
dropped afterward) and extracts the STL archive into a scratch directory, then asserts:

1. `manifest.sha256` matches the on-disk `db.dump` / `originals.tar.gz` bytes.
2. The restored database actually contains rows (a real reload, not an empty schema).
3. The number of extracted `*.stl` files equals `SELECT count(*) FROM assets` in the restored
   database — the two halves of a backup (DB dump, STL archive) are mutually consistent.

Run this after every deploy that changes the backup scripts, and periodically in general — an
unverified backup does not count as a backup.

### Full restore onto a real environment

1. Stop the application services so nothing writes during the restore:
   ```sh
   docker compose stop backend geometry-worker
   ```
2. Pick the backup to restore (default: the newest under `./backups/daily/`):
   ```sh
   BACKUP_DIR=./backups/daily/<timestamp>
   ```
3. Verify its manifest before touching anything:
   ```sh
   ( cd "$BACKUP_DIR" && sha256sum -c manifest.sha256 )
   ```
4. Restore the database (into the real database — only do this when you actually intend to
   replace current data; recreate the target database first if it must be empty):
   ```sh
   docker compose exec -T postgres pg_restore --no-owner --no-privileges \
     -U "$POSTGRES_USER" -d "$POSTGRES_DB" - < "$BACKUP_DIR/db.dump"
   ```
5. Restore the original STLs into `./data`:
   ```sh
   tar -xzf "$BACKUP_DIR/originals.tar.gz" -C ./data
   ```
6. Restart the application services and confirm health:
   ```sh
   docker compose up -d backend geometry-worker
   docker compose ps
   ```

## Known gaps

**Backups are local to the VPS host and do not survive loss of that host.** This is a deliberate,
accepted gap for this phase (decision D3), not an oversight — off-host replication (encrypted
rsync to a second destination, object storage, PITR/WAL archiving, provider volume snapshots)
is explicitly deferred. If the VPS is lost, destroyed, or its disk fails, everything under
`./backups` (and `./data`) is lost with it.

| Scenario | Covered by this backup? |
|---|---|
| Accidental data deletion / bad migration on the same host | Yes — restore from `./backups` |
| Database corruption on the same host | Yes — restore from `./backups` |
| Disk failure on the VPS | **No** — `./backups` lives on the same disk as `./data` and the database volume |
| VPS lost, destroyed, or the provider account terminated | **No** — nothing leaves the host |
| Ransomware/compromise of the host itself | **No** — an attacker with host access can delete `./backups` too |

Do not treat this runbook as disaster-recovery-from-host-loss guidance. If that guarantee is
ever required, it needs a genuinely separate destination (a second host, or object storage),
which is out of scope here and tracked as deferred work in ADR-0008.

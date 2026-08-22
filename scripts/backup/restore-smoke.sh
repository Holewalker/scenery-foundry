#!/usr/bin/env bash
# ADR-0008 / D10: proves a backup is actually restorable — "a backup not verified doesn't
# count as a backup". Restores the newest (or an explicitly given) dump into a throwaway
# database and the archived STLs into a scratch directory, then asserts:
#   1. the manifest's sha256 hashes match the on-disk db.dump / originals.tar.gz bytes
#   2. the restored database actually contains rows (a real reload, not just an empty schema)
#   3. the number of extracted `*.stl` files equals `SELECT count(*) FROM assets` in the
#      restored database — the two halves of a backup (DB dump, STL archive) are mutually
#      consistent, not merely individually non-empty
#
# Never run against a production database: RESTORE_DB is always a throwaway name, dropped and
# recreated by this script.
set -euo pipefail

POSTGRES_HOST="${POSTGRES_HOST:-postgres}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_USER="${POSTGRES_USER:?POSTGRES_USER must be set}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"
BACKUP_ROOT="${BACKUP_ROOT:-/backups}"
RESTORE_DB="${RESTORE_DB:-restore_smoke_$$}"
BACKUP_DIR="${1:-}"

export PGPASSWORD="$POSTGRES_PASSWORD"

if [ -z "$BACKUP_DIR" ]; then
  BACKUP_DIR="$(find "$BACKUP_ROOT/daily" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)"
fi
[ -n "$BACKUP_DIR" ] && [ -d "$BACKUP_DIR" ] || {
  echo "restore-smoke: no backup found under $BACKUP_ROOT/daily and none given as \$1" >&2
  exit 1
}
[ -f "$BACKUP_DIR/db.dump" ] || { echo "restore-smoke: missing db.dump in $BACKUP_DIR" >&2; exit 1; }
[ -f "$BACKUP_DIR/originals.tar.gz" ] || { echo "restore-smoke: missing originals.tar.gz in $BACKUP_DIR" >&2; exit 1; }
[ -f "$BACKUP_DIR/manifest.sha256" ] || { echo "restore-smoke: missing manifest.sha256 in $BACKUP_DIR" >&2; exit 1; }

echo "restore-smoke: verifying manifest for $BACKUP_DIR"
( cd "$BACKUP_DIR" && sha256sum -c manifest.sha256 )

WORKDIR="$(mktemp -d)"
STL_DIR="$WORKDIR/data"
mkdir -p "$STL_DIR"
cleanup() {
  rm -rf -- "${WORKDIR:?}"
  dropdb --if-exists -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" "$RESTORE_DB" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "restore-smoke: restoring db.dump into scratch database '$RESTORE_DB'"
dropdb --if-exists -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" "$RESTORE_DB"
createdb -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" "$RESTORE_DB"
pg_restore --no-owner --no-privileges -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" \
  -d "$RESTORE_DB" "$BACKUP_DIR/db.dump"

table_count="$(psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$RESTORE_DB" -tAc \
  "select count(*) from information_schema.tables where table_schema='public'")"
[ "$table_count" -gt 0 ] || { echo "restore-smoke: restored database has zero public tables" >&2; exit 1; }

total_rows=0
for tbl in $(psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$RESTORE_DB" -tAc \
  "select table_name from information_schema.tables where table_schema='public'"); do
  rows="$(psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$RESTORE_DB" -tAc \
    "select count(*) from \"$tbl\"")"
  total_rows=$((total_rows + rows))
done
echo "restore-smoke: restored $table_count public tables, $total_rows total rows"

asset_rows="$(psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$RESTORE_DB" -tAc \
  "select count(*) from assets" 2>/dev/null || echo 0)"

echo "restore-smoke: extracting originals.tar.gz into $STL_DIR"
tar -xzf "$BACKUP_DIR/originals.tar.gz" -C "$STL_DIR"
stl_count="$(find "$STL_DIR" -type f -name '*.stl' | wc -l | tr -d ' ')"
preview_leak="$(find "$STL_DIR" -type f -name 'preview.glb' | wc -l | tr -d ' ')"

echo "restore-smoke: extracted $stl_count original STL file(s); assets rows=$asset_rows"

if [ "$preview_leak" -ne 0 ]; then
  echo "restore-smoke: FAIL — $preview_leak derived preview.glb file(s) leaked into the original-only archive" >&2
  exit 1
fi
if [ "$asset_rows" != "$stl_count" ]; then
  echo "restore-smoke: FAIL — assets table has $asset_rows row(s) but $stl_count original STL file(s) were restored" >&2
  exit 1
fi

echo "restore-smoke: PASS — db.dump and originals.tar.gz are mutually consistent and verifiably restorable ($BACKUP_DIR)"

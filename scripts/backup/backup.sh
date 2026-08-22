#!/usr/bin/env bash
# ADR-0008 / D10: nightly Postgres dump + original-STL-only archive, atomically published,
# with 7-daily + 4-weekly retention (D6). Runs inside the `backup` sidecar (reuses the
# `postgres:18.4` image already pulled for the `postgres` service, so `pg_dump`'s version
# always matches the server's).
#
# Injection safety (threat matrix): every value that can come from the environment
# (POSTGRES_DB/USER/HOST/PORT) is passed to `pg_dump` as a SEPARATE argv element, never
# interpolated into a composed shell string or passed to `eval`. A `POSTGRES_DB` containing a
# space or a `;` is therefore just an unusual (or non-existent) database name to libpq — never
# a second shell command.
#
# Source this file (`BACKUP_LIB_ONLY=1 . backup.sh`) to reuse `prune_dir`/`is_weekly_ts` in
# tests without running `main`.
set -euo pipefail

DATA_ROOT="${DATA_ROOT:-/data}"
BACKUP_ROOT="${BACKUP_ROOT:-/backups}"
BACKUP_KEEP_DAILY="${BACKUP_KEEP_DAILY:-7}"
BACKUP_KEEP_WEEKLY="${BACKUP_KEEP_WEEKLY:-4}"
POSTGRES_HOST="${POSTGRES_HOST:-postgres}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
# The required (no-default) POSTGRES_* credentials are validated inside main(), NOT here at
# source time, so `BACKUP_LIB_ONLY=1 . backup.sh` can reuse `prune_dir`/`is_weekly_ts` in tests
# without a live database or any of these variables set.

# json-escapes a value for use inside a double-quoted JSON string (only characters this
# script ever emits: paths, statuses, numbers-as-strings — still escaped defensively).
json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

log_json() {
  status="$1"; db_bytes="$2"; stl_bytes="$3"; duration_ms="$4"; pruned_count="$5"; message="$6"
  ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '{"ts":"%s","level":"%s","service.name":"backup","status":"%s","dbBytes":%s,"stlBytes":%s,"durationMs":%s,"prunedCount":%s,"message":"%s"}\n' \
    "$ts" "$([ "$status" = "success" ] && echo INFO || echo ERROR)" "$(json_escape "$status")" \
    "$db_bytes" "$stl_bytes" "$duration_ms" "$pruned_count" "$(json_escape "$message")"
}

# Returns 0 (true) when the date portion of a `YYYYMMDDT...Z` timestamp falls on a Sunday.
# Pure function of its argument — never reads the wall clock itself — so it is directly
# testable with fixed timestamps instead of depending on which day the test happens to run.
is_weekly_ts() {
  ts="$1"
  day="${ts%%T*}"
  [ "$(date -u -d "$day" +%u)" = "7" ]
}

# Keeps only the `keep` lexicographically-last (== chronologically-last, given the
# `YYYYMMDDTHHMMSSZ` naming) entries directly under `dir`; deletes the rest. A no-op when
# `dir` does not exist yet or already holds `keep` or fewer entries.
prune_dir() {
  dir="$1"; keep="$2"
  [ -d "$dir" ] || return 0
  entries="$(find "$dir" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort)"
  count="$(printf '%s\n' "$entries" | grep -c . || true)"
  [ "$count" -gt "$keep" ] || { printf '0'; return 0; }
  excess=$((count - keep))
  printf '%s\n' "$entries" | head -n "$excess" | while IFS= read -r name; do
    [ -n "$name" ] || continue
    rm -rf -- "${dir:?}/${name:?}"
  done
  printf '%s' "$excess"
}

# Builds `originals.tar.gz` containing ONLY original STL uploads (D5): every file under
# `assets/*/*.stl`. The `assets/<assetId>/` directory also holds `preview.glb` (published by
# the geometry-worker's ASSET_PROCESSING pipeline right next to the original) and the
# top-level `pieces/`/`exports/` namespaces hold combined-export/pieces-export derived
# artifacts entirely outside `assets/` — none of that is reachable by the `*.stl` filter, so
# only immutable, non-regenerable originals ever enter the archive.
stage_originals() {
  staging="$1"
  list="$staging/.originals.list"
  : > "$list"
  if [ -d "$DATA_ROOT/assets" ]; then
    ( cd "$DATA_ROOT" && find assets -type f -name '*.stl' -print0 ) > "$list"
  fi
  if [ -s "$list" ]; then
    # -C must appear BEFORE -T on GNU tar's command line: it only affects options/files that
    # follow it positionally, so the paths read from `$list` (which are relative to
    # DATA_ROOT) resolve correctly only with this exact ordering.
    tar -C "$DATA_ROOT" --null -T "$list" -czf "$staging/originals.tar.gz"
  else
    tar -czf "$staging/originals.tar.gz" -T /dev/null
  fi
  rm -f "$list"
}

main() {
  POSTGRES_DB="${POSTGRES_DB:?POSTGRES_DB must be set}"
  POSTGRES_USER="${POSTGRES_USER:?POSTGRES_USER must be set}"
  POSTGRES_PASSWORD="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"

  start_epoch="$(date -u +%s%3N)"
  ts="${BACKUP_TS:-$(date -u +%Y%m%dT%H%M%SZ)}"
  staging="$BACKUP_ROOT/.staging/$ts"
  mkdir -p "$staging"

  staging_cleanup() {
    rm -rf -- "${staging:?}"
    printf 'failed\n' > "$BACKUP_ROOT/last-status" 2>/dev/null || true
  }
  trap 'staging_cleanup; log_json "failed" 0 0 0 0 "backup step failed before publish"' ERR

  PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -Fc \
    -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -f "$staging/db.dump"

  stage_originals "$staging"

  ( cd "$staging" && sha256sum db.dump originals.tar.gz > manifest.sha256 )
  sync

  db_bytes="$(stat -c%s "$staging/db.dump")"
  stl_bytes="$(stat -c%s "$staging/originals.tar.gz")"

  mkdir -p "$BACKUP_ROOT/daily" "$BACKUP_ROOT/weekly"
  mv "$staging" "$BACKUP_ROOT/daily/$ts"
  trap - ERR

  pruned_daily="$(prune_dir "$BACKUP_ROOT/daily" "$BACKUP_KEEP_DAILY")"
  pruned_weekly=0
  if is_weekly_ts "$ts" && [ -d "$BACKUP_ROOT/daily/$ts" ]; then
    cp -al "$BACKUP_ROOT/daily/$ts" "$BACKUP_ROOT/weekly/$ts"
    pruned_weekly="$(prune_dir "$BACKUP_ROOT/weekly" "$BACKUP_KEEP_WEEKLY")"
  fi

  end_epoch="$(date -u +%s%3N)"
  duration_ms=$((end_epoch - start_epoch))
  pruned_count=$((pruned_daily + pruned_weekly))

  printf 'ok\n' > "$BACKUP_ROOT/last-status"
  log_json "success" "$db_bytes" "$stl_bytes" "$duration_ms" "$pruned_count" "backup published to daily/$ts"
}

if [ "${BACKUP_LIB_ONLY:-0}" != "1" ]; then
  main "$@"
fi

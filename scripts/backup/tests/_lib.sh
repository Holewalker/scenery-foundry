#!/usr/bin/env bash
# Shared docker-based test harness for the backup shell scripts. Sourced by each test file.
# Spins up a real, throwaway `postgres:18.4` container per test (project convention: no DB
# mocks — see PR3's geometry-worker tests) and runs `backup.sh`/`restore-smoke.sh` inside
# fresh `postgres:18.4` containers on the same Docker network, exactly as the `backup`
# Compose sidecar will in production.
set -euo pipefail

# Windows/Git-Bash + Docker Desktop note: `docker run -v` needs a real Windows-style host path
# (`cygpath -w`) with MSYS path conversion disabled, or MSYS mangles the argument into a
# nonsense path under the Git installation directory. `hostpath()` is a no-op passthrough on
# Linux (no `cygpath`), which is what the VPS/CI actually run on.
export MSYS_NO_PATHCONV=1
hostpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SUFFIX="$$-$(date +%s)"
PG_TEST_NET="sf-backup-test-net-$SUFFIX"
PG_TEST_CONTAINER="sf-backup-test-pg-$SUFFIX"
PG_TEST_PASSWORD="test-password"
WORKDIR="$(mktemp -d)"

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

start_test_postgres() {
  docker network create "$PG_TEST_NET" >/dev/null
  docker run -d --name "$PG_TEST_CONTAINER" --network "$PG_TEST_NET" \
    -e POSTGRES_PASSWORD="$PG_TEST_PASSWORD" postgres:18.4 >/dev/null
  for _ in $(seq 1 60); do
    if docker exec "$PG_TEST_CONTAINER" pg_isready -U postgres >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  fail "test postgres container did not become ready in time"
}

psql_test() {
  docker exec -e PGPASSWORD="$PG_TEST_PASSWORD" "$PG_TEST_CONTAINER" \
    psql -U postgres -v ON_ERROR_STOP=1 "$@"
}

cleanup_test_harness() {
  docker rm -f "$PG_TEST_CONTAINER" >/dev/null 2>&1 || true
  docker network rm "$PG_TEST_NET" >/dev/null 2>&1 || true
  rm -rf -- "${WORKDIR:?}"
}
trap cleanup_test_harness EXIT

# Runs backup.sh inside a throwaway postgres:18.4 container on the test network. Extra
# `-e KEY=VALUE` env args come first, then any positional args are ignored (backup.sh takes
# none). $1 = data dir (host path, mounted ro at /data), $2 = backups dir (host path, mounted
# rw at /backups), remaining args are passed through as `-e KEY=VALUE` to `docker run`.
run_backup() {
  data_dir="$1"; backups_dir="$2"; shift 2
  docker run --rm --network "$PG_TEST_NET" \
    -e POSTGRES_HOST="$PG_TEST_CONTAINER" -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD="$PG_TEST_PASSWORD" \
    "$@" \
    -v "$(hostpath "$SCRIPT_DIR/backup.sh"):/scripts/backup.sh:ro" \
    -v "$(hostpath "$data_dir"):/data:ro" \
    -v "$(hostpath "$backups_dir"):/backups" \
    postgres:18.4 bash /scripts/backup.sh
}

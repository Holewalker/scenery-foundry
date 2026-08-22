#!/usr/bin/env bash
# Task 4.1 (RED then GREEN): a `POSTGRES_DB` value containing a space AND a `;` must dump
# correctly (proving pg_dump actually targeted that literal database name) and must NOT
# execute anything extra (proving the value was passed as a single argv element to pg_dump,
# never interpolated into a shell command string). Runs backup.sh inside a real, throwaway
# postgres:18.4 container against a real, throwaway postgres:18.4 server — no mocks.
set -euo pipefail
cd -- "$(dirname -- "$0")"
. ./_lib.sh

DB_NAME='scenery db; touch /canary/pwned'

echo "== test-injection-safety: starting throwaway Postgres =="
start_test_postgres

echo "== creating database with a hostile-looking literal name: [$DB_NAME] =="
psql_test -c "CREATE DATABASE \"$DB_NAME\""
psql_test -d "$DB_NAME" -c 'CREATE TABLE canary(id int); INSERT INTO canary(id) VALUES (1), (2), (3);'

data_dir="$WORKDIR/data"; backups_dir="$WORKDIR/backups"; canary_dir="$WORKDIR/canary"
mkdir -p "$data_dir" "$backups_dir" "$canary_dir"

echo "== running backup.sh with POSTGRES_DB=[$DB_NAME] =="
run_backup "$data_dir" "$backups_dir" \
  -e POSTGRES_DB="$DB_NAME" \
  -v "$(hostpath "$canary_dir"):/canary"

daily_dir="$(find "$backups_dir/daily" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
[ -n "$daily_dir" ] || fail "no directory was published under backups/daily/"
[ -s "$daily_dir/db.dump" ] || fail "db.dump was not produced (or is empty) for a POSTGRES_DB with a space and a ';'"

restore_list="$(docker run --rm --network "$PG_TEST_NET" \
  -v "$(hostpath "$daily_dir/db.dump"):/db.dump:ro" \
  postgres:18.4 pg_restore --list /db.dump)"
echo "$restore_list" | grep -Fq "dbname: $DB_NAME" || fail "db.dump's TOC header does not record dbname [$DB_NAME] — pg_dump may not have targeted the intended database"
echo "$restore_list" | grep -Eq 'TABLE( DATA)? public canary postgres' || fail "db.dump does not contain the 'canary' table — pg_dump did not target the intended database"

if [ -e "$canary_dir/pwned" ]; then
  fail "INJECTION: /canary/pwned was created — POSTGRES_DB was executed as a shell command instead of passed as data"
fi

echo "PASS: POSTGRES_DB containing a space and ';' dumped correctly (canary table present) with no extra command execution"

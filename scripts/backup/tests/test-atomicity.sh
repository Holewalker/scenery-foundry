#!/usr/bin/env bash
# Task 4.2 (RED then GREEN): an interrupted staging run (here: pg_dump fails part-way through
# the job, after the staging directory already exists) must leave NOTHING under `daily/` —
# publish is a single atomic `mv` of a fully-staged directory, never a partial one. Runs
# against a real, throwaway postgres:18.4 server (wrong password forces a real, late pg_dump
# failure rather than an early argument-validation error).
set -euo pipefail
cd -- "$(dirname -- "$0")"
. ./_lib.sh

echo "== test-atomicity: starting throwaway Postgres =="
start_test_postgres
psql_test -c "CREATE DATABASE atomicity_test"

data_dir="$WORKDIR/data"; backups_dir="$WORKDIR/backups"
mkdir -p "$data_dir" "$backups_dir"

echo "== running backup.sh with a wrong password, forcing pg_dump to fail after staging began =="
if run_backup "$data_dir" "$backups_dir" \
  -e POSTGRES_DB=atomicity_test \
  -e POSTGRES_PASSWORD=deliberately-wrong-password \
  2>"$WORKDIR/backup.stderr"; then
  fail "backup.sh exited 0 despite an authentication failure — should have propagated the pg_dump failure"
fi
echo "(expected failure output)"; cat "$WORKDIR/backup.stderr"

if [ -d "$backups_dir/daily" ]; then
  published_count="$(find "$backups_dir/daily" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' ')"
  [ "$published_count" -eq 0 ] || fail "an interrupted run left $published_count entr(ies) under backups/daily/ — publish must be all-or-nothing"
fi

if [ -d "$backups_dir/.staging" ]; then
  staging_count="$(find "$backups_dir/.staging" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' ')"
  [ "$staging_count" -eq 0 ] || fail "an interrupted run left $staging_count leftover staging entr(ies) — expected the ERR trap to clean up"
fi

echo "PASS: an interrupted staging run left nothing under daily/ (and cleaned up its own staging entry)"

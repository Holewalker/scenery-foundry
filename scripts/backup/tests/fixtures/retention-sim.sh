#!/usr/bin/env bash
# Simulates 40 consecutive daily publishes (2026-01-01T03:00:00Z .. 2026-02-09T03:00:00Z) by
# driving backup.sh's own `prune_dir`/`is_weekly_ts` functions directly — no pg_dump, no real
# database — and asserts the result holds exactly 7 daily + 4 weekly entries (D6), and that
# they are exactly the chronologically-last ones. Runs inside a postgres:18.4 container so
# `date -u -d` / `find -printf` (GNU extensions backup.sh itself relies on) are guaranteed
# present, matching the real backup sidecar's runtime.
set -euo pipefail
BACKUP_LIB_ONLY=1 . /scripts/backup.sh

BACKUP_ROOT=/backups
mkdir -p "$BACKUP_ROOT/daily" "$BACKUP_ROOT/weekly"

start_epoch="$(date -u -d "2026-01-01T03:00:00Z" +%s)"
day_seconds=86400

for i in $(seq 0 39); do
  epoch=$((start_epoch + i * day_seconds))
  ts="$(date -u -d "@$epoch" +%Y%m%dT%H%M%SZ)"
  mkdir -p "$BACKUP_ROOT/daily/$ts"
  echo marker > "$BACKUP_ROOT/daily/$ts/marker"
  prune_dir "$BACKUP_ROOT/daily" 7 >/dev/null
  if is_weekly_ts "$ts"; then
    cp -al "$BACKUP_ROOT/daily/$ts" "$BACKUP_ROOT/weekly/$ts"
    prune_dir "$BACKUP_ROOT/weekly" 4 >/dev/null
  fi
done

daily_count="$(find "$BACKUP_ROOT/daily" -mindepth 1 -maxdepth 1 -type d | wc -l)"
weekly_count="$(find "$BACKUP_ROOT/weekly" -mindepth 1 -maxdepth 1 -type d | wc -l)"
echo "daily_count=$daily_count weekly_count=$weekly_count"

[ "$daily_count" -eq 7 ] || { echo "FAIL: expected 7 daily entries, got $daily_count"; exit 1; }
[ "$weekly_count" -eq 4 ] || { echo "FAIL: expected 4 weekly entries, got $weekly_count"; exit 1; }

expected_daily="$(for i in $(seq 33 39); do date -u -d "@$((start_epoch + i * day_seconds))" +%Y%m%dT%H%M%SZ; done | sort)"
actual_daily="$(find "$BACKUP_ROOT/daily" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort)"
[ "$expected_daily" = "$actual_daily" ] || {
  echo "FAIL: retained daily set mismatch"; echo "expected:"; echo "$expected_daily"; echo "actual:"; echo "$actual_daily"; exit 1;
}

all_sundays=""
for i in $(seq 0 39); do
  ts="$(date -u -d "@$((start_epoch + i * day_seconds))" +%Y%m%dT%H%M%SZ)"
  is_weekly_ts "$ts" && all_sundays="$all_sundays $ts"
done
expected_weekly="$(printf '%s\n' $all_sundays | sort | tail -n 4 | sort)"
actual_weekly="$(find "$BACKUP_ROOT/weekly" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort)"
[ "$expected_weekly" = "$actual_weekly" ] || {
  echo "FAIL: retained weekly set mismatch"; echo "expected:"; echo "$expected_weekly"; echo "actual:"; echo "$actual_weekly"; exit 1;
}

echo "PASS: daily=7 weekly=4, exactly the chronologically-last entries over a simulated 40-day sequence"

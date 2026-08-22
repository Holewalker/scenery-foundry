#!/usr/bin/env bash
# ADR-0008 / D10: sleeps until the next configured UTC hour, then runs backup.sh, forever.
# A Compose sidecar loop instead of host cron or a new cron binary in the image (D10) — this
# keeps the whole backup mechanism visible in `docker compose ps` and needs no host privileges.
set -euo pipefail

BACKUP_HOUR_UTC="${BACKUP_HOUR_UTC:-3}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

seconds_until_next_run() {
  hour="$1"
  now_epoch="$(date -u +%s)"
  today_epoch="$(date -u -d "today ${hour}:00:00" +%s)"
  if [ "$today_epoch" -gt "$now_epoch" ]; then
    printf '%s' "$((today_epoch - now_epoch))"
  else
    tomorrow_epoch="$(date -u -d "tomorrow ${hour}:00:00" +%s)"
    printf '%s' "$((tomorrow_epoch - now_epoch))"
  fi
}

main() {
  while true; do
    wait_seconds="$(seconds_until_next_run "$BACKUP_HOUR_UTC")"
    sleep "$wait_seconds"
    "$SCRIPT_DIR/backup.sh" || true
  done
}

if [ "${RUN_SH_LIB_ONLY:-0}" != "1" ]; then
  main
fi

#!/usr/bin/env bash
# Task 4.3 (RED then GREEN): retention pruning keeps exactly 7 daily + 4 weekly backups (D6)
# over a simulated 40-day sequence, never more, never fewer, and always the chronologically
# newest ones. Pure logic test (no pg_dump, no real database) — see fixtures/retention-sim.sh.
set -euo pipefail
cd -- "$(dirname -- "$0")"
. ./_lib.sh

backups_dir="$WORKDIR/backups"
mkdir -p "$backups_dir"

echo "== test-retention: simulating 40 daily publishes against backup.sh's prune_dir/is_weekly_ts =="
docker run --rm \
  -v "$(hostpath "$SCRIPT_DIR/backup.sh"):/scripts/backup.sh:ro" \
  -v "$(hostpath "$PWD/fixtures/retention-sim.sh"):/scripts/retention-sim.sh:ro" \
  -v "$(hostpath "$backups_dir"):/backups" \
  postgres:18.4 bash /scripts/retention-sim.sh

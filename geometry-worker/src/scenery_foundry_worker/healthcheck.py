"""Real worker healthcheck (ADR-0008 D9): liveness marker freshness + independent DB probe.

Deliberately NOT the ADR-0005 `geometry_jobs` heartbeat table (as the original proposal assumed).
That heartbeat is a per-job lease renewed only while a job is claimed/running, so an idle worker
(empty queue, nothing claimed) renews nothing — a heartbeat-freshness check would misreport a
perfectly healthy idle worker as unhealthy. Instead: a liveness marker file touched by `main.py`'s
poll loop every cycle, whether or not a job was claimed. Because `claim_job` queries the DB on
every poll and a DB failure propagates by design, a fresh marker also implies recent DB
reachability — but the healthcheck still runs its own independent `SELECT 1` probe so a
stale-but-not-yet-expired marker never masks a DB outage that started after the last touch.
"""

from __future__ import annotations

import os
import sys
import time
from pathlib import Path

import psycopg

DEFAULT_LIVENESS_PATH = Path("/tmp/scenery-foundry-worker-liveness")
# Generous multiple of the default 2s `WORKER_POLL_SECONDS` interval, so normal poll jitter or a
# single slow job never falsely trips the healthcheck between poll cycles.
DEFAULT_MAX_AGE_SECONDS = 30.0
_DB_CONNECT_TIMEOUT_SECONDS = 3


def liveness_marker_path() -> Path:
    return Path(os.environ.get("WORKER_LIVENESS_PATH", str(DEFAULT_LIVENESS_PATH)))


def touch_liveness_marker(path: Path | None = None) -> None:
    """Called once per poll cycle by `main.py`'s loop, whether or not a job was claimed."""
    target = path if path is not None else liveness_marker_path()
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(str(time.time()))


def _marker_is_fresh(path: Path, max_age_seconds: float) -> bool:
    try:
        age = time.time() - path.stat().st_mtime
    except FileNotFoundError:
        return False
    return age <= max_age_seconds


def _database_is_reachable(database_url: str) -> bool:
    try:
        with psycopg.connect(database_url, connect_timeout=_DB_CONNECT_TIMEOUT_SECONDS) as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT 1")
                cur.fetchone()
        return True
    except Exception:
        return False


def check_health(
    path: Path | None = None,
    max_age_seconds: float = DEFAULT_MAX_AGE_SECONDS,
    database_url: str | None = None,
) -> bool:
    """Healthy only when BOTH the liveness marker is fresh AND the DB is independently reachable."""
    marker_path = path if path is not None else liveness_marker_path()
    if not _marker_is_fresh(marker_path, max_age_seconds):
        return False
    url = database_url if database_url is not None else os.environ.get("WORKER_DATABASE_URL")
    if not url:
        return False
    return _database_is_reachable(url)


def main() -> None:
    """CLI entry point for `docker-compose.yml`'s healthcheck (`python -m ...healthcheck`)."""
    sys.exit(0 if check_health() else 1)


if __name__ == "__main__":
    main()

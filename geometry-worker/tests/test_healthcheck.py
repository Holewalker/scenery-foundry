"""Task 3.2: real worker healthcheck (ADR-0008 D9).

D9 correction to the original proposal: the ADR-0005 `geometry_jobs` heartbeat is a per-job
lease, renewed only while a job is claimed. An idle worker (empty queue) renews nothing, so a
heartbeat-based check would misreport a healthy idle worker as unhealthy. Instead: a liveness
marker file touched every poll cycle (claimed or not) + an independent DB probe. Uses a real
Postgres via the shared `postgres_dsn` fixture (project convention: no mocks for DB behavior).
"""

from __future__ import annotations

import os
import time

from scenery_foundry_worker.healthcheck import (
    check_health,
    liveness_marker_path,
    touch_liveness_marker,
)


def test_passes_when_marker_is_fresh_and_db_is_reachable(tmp_path, postgres_dsn):
    marker = tmp_path / "liveness"
    touch_liveness_marker(marker)

    assert check_health(path=marker, database_url=postgres_dsn) is True


def test_fails_when_marker_is_stale_even_though_db_is_reachable(tmp_path, postgres_dsn):
    marker = tmp_path / "liveness"
    touch_liveness_marker(marker)
    stale_time = time.time() - 3600  # 1 hour old, far beyond any reasonable poll interval
    os.utime(marker, (stale_time, stale_time))

    assert check_health(path=marker, database_url=postgres_dsn, max_age_seconds=30.0) is False


def test_fails_when_marker_is_missing(tmp_path, postgres_dsn):
    marker = tmp_path / "does-not-exist"

    assert check_health(path=marker, database_url=postgres_dsn) is False


def test_fails_when_db_is_unreachable_even_though_marker_is_fresh(tmp_path):
    marker = tmp_path / "liveness"
    touch_liveness_marker(marker)
    unreachable_dsn = "postgresql://user:pass@127.0.0.1:1/nonexistent"

    assert check_health(path=marker, database_url=unreachable_dsn) is False


def test_fails_when_database_url_is_not_configured(tmp_path):
    marker = tmp_path / "liveness"
    touch_liveness_marker(marker)

    assert check_health(path=marker, database_url=None) is False


def test_touch_liveness_marker_creates_parent_directories(tmp_path):
    marker = tmp_path / "nested" / "dir" / "liveness"

    touch_liveness_marker(marker)

    assert marker.exists()


def test_liveness_marker_path_defaults_and_respects_env_override(monkeypatch, tmp_path):
    monkeypatch.delenv("WORKER_LIVENESS_PATH", raising=False)
    default_path = liveness_marker_path()
    assert str(default_path) != ""

    override = tmp_path / "custom-liveness"
    monkeypatch.setenv("WORKER_LIVENESS_PATH", str(override))
    assert liveness_marker_path() == override

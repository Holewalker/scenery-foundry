import os
import signal
import threading
import traceback
from pathlib import Path

import psycopg

from .claim import claim_job
from .pipeline import PipelineResult, finalize_job, process_job

JOB_TYPE = "ASSET_PROCESSING"


def worker_identity() -> str:
    return "scenery-foundry.geometry-worker/v1"


def run_once(
    conn: psycopg.Connection, data_root: Path, worker_id: str, lease_seconds: int = 120
) -> bool:
    """Claims and fully processes one job. Returns `False` when the queue had nothing eligible.

    `process_job` is isolated in its own try/except: an unhandled exception there means this job's
    input is bad, not that the worker process is unhealthy, so it must fail only this job and let
    the loop continue. `claim_job`/`finalize_job` stay OUTSIDE the boundary — a failure there is a
    connectivity/DB problem and must propagate so the container crash-loops instead of spinning.
    """
    job = claim_job(conn, JOB_TYPE, worker_id, lease_seconds)
    if job is None:
        return False
    try:
        result = process_job(job, data_root)
    except Exception:
        traceback.print_exc()
        result = PipelineResult(
            "FAILED",
            '{"geometryStatus":"UNKNOWN","errorCode":"PROCESSING_ERROR"}',
            error_code="PROCESSING_ERROR",
        )
    finalize_job(conn, job, result)
    return True


def main() -> None:
    database_url = os.environ.get("WORKER_DATABASE_URL")
    if not database_url:
        raise SystemExit("WORKER_DATABASE_URL is required")

    stopped = threading.Event()

    def stop(_signum: int, _frame: object) -> None:
        stopped.set()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    print(worker_identity(), flush=True)

    data_root = Path(os.environ.get("GEOMETRY_DATA_ROOT", "/data"))
    lease_seconds = int(os.environ.get("WORKER_LEASE_SECONDS", "120"))
    poll_seconds = float(os.environ.get("WORKER_POLL_SECONDS", "2"))
    worker_id = os.environ.get("HOSTNAME", worker_identity())

    with psycopg.connect(database_url, autocommit=False) as conn:
        while not stopped.is_set():
            handled = run_once(conn, data_root, worker_id, lease_seconds)
            if not handled:
                stopped.wait(poll_seconds)


if __name__ == "__main__":
    main()

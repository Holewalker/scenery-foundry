import os
import signal
import threading
from pathlib import Path

import psycopg

from .claim import claim_job
from .pipeline import finalize_job, process_job

JOB_TYPE = "ASSET_PROCESSING"


def worker_identity() -> str:
    return "scenery-foundry.geometry-worker/v1"


def run_once(
    conn: psycopg.Connection, data_root: Path, worker_id: str, lease_seconds: int = 120
) -> bool:
    """Claims and fully processes one job. Returns `False` when the queue had nothing eligible."""
    job = claim_job(conn, JOB_TYPE, worker_id, lease_seconds)
    if job is None:
        return False
    result = process_job(job, data_root)
    finalize_job(conn, job, result)
    return True


def main() -> None:
    stopped = threading.Event()

    def stop(_signum: int, _frame: object) -> None:
        stopped.set()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    print(worker_identity(), flush=True)

    database_url = os.environ.get("WORKER_DATABASE_URL")
    if not database_url:
        stopped.wait()
        return

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

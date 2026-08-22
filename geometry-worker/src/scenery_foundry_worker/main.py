import json
import os
import signal
import threading
import traceback
from pathlib import Path

import psycopg

from .claim import claim_job
from .job_result import PipelineResult
from .pipeline import finalize_job, process_job

# Both job types are claimable by this single-worker loop (Phase 4 design). The try-order rotates
# each poll (`_claim_next`) so a saturated ASSET_PROCESSING queue cannot starve COMBINED_EXPORT
# jobs — a fixed ASSET_PROCESSING-first order would let asset uploads indefinitely delay exports.
JOB_TYPES = ("ASSET_PROCESSING", "COMBINED_EXPORT")


def worker_identity() -> str:
    return "scenery-foundry.geometry-worker/v1"


def _claim_next(
    conn: psycopg.Connection, worker_id: str, lease_seconds: int, poll_index: int
):
    """Tries each job type in a rotated order for this poll, returning the first claimed job."""
    offset = poll_index % len(JOB_TYPES)
    order = JOB_TYPES[offset:] + JOB_TYPES[:offset]
    for job_type in order:
        job = claim_job(conn, job_type, worker_id, lease_seconds)
        if job is not None:
            return job
    return None


def run_once(
    conn: psycopg.Connection,
    data_root: Path,
    worker_id: str,
    lease_seconds: int = 120,
    poll_index: int = 0,
) -> bool:
    """Claims and fully processes one job (of either type). Returns `False` when nothing eligible.

    `process_job` is isolated in its own try/except: an unhandled exception there means this job's
    input is bad, not that the worker process is unhealthy, so it must fail only this job and let
    the loop continue. `claim_job`/`finalize_job` stay OUTSIDE the boundary — a failure there is a
    connectivity/DB problem and must propagate so the container crash-loops instead of spinning.
    """
    job = _claim_next(conn, worker_id, lease_seconds, poll_index)
    if job is None:
        return False
    try:
        result = process_job(job, data_root)
    except Exception:
        traceback.print_exc()
        if job.payload.get("jobType") == "COMBINED_EXPORT":
            # Deliberately omits `geometryStatus`: that key is ASSET_PROCESSING/AssetProjector's
            # contract and must not appear on a COMBINED_EXPORT row (Phase 4 design).
            diagnostics_json = json.dumps(
                {"exportStatus": "FAILED", "errorCode": "PROCESSING_ERROR"}
            )
        else:
            diagnostics_json = json.dumps(
                {"geometryStatus": "UNKNOWN", "errorCode": "PROCESSING_ERROR"}
            )
        result = PipelineResult("FAILED", diagnostics_json, error_code="PROCESSING_ERROR")
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

    poll_index = 0
    with psycopg.connect(database_url, autocommit=False) as conn:
        while not stopped.is_set():
            handled = run_once(conn, data_root, worker_id, lease_seconds, poll_index)
            poll_index += 1
            if not handled:
                stopped.wait(poll_seconds)


if __name__ == "__main__":
    main()

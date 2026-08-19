"""Claim loop primitives (ADR-0005): checksum verification and the `FOR UPDATE SKIP LOCKED` claim.

Mirrors `backend/.../geometryjob/JobRepository.java`'s claim SQL exactly so both runtimes share
the same fencing contract; the worker's DB grant is SELECT/UPDATE on `geometry_jobs` only
(ADR-0002 D3).
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from uuid import UUID

import psycopg


class ChecksumMismatchError(ValueError):
    """Raised when a claimed job's `original.stl` bytes no longer match its recorded sha256."""


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def verify_checksum(path: Path, expected_sha256: str) -> None:
    """Verifies `path`'s sha256 BEFORE any mesh parsing is attempted (ADR-0002 checksum gate)."""
    actual = sha256_hex(Path(path).read_bytes())
    if actual != expected_sha256:
        raise ChecksumMismatchError(f"expected sha256 {expected_sha256}, read {actual}, for {path}")


@dataclass(frozen=True)
class ClaimedJob:
    id: UUID
    owner_id: UUID
    subject_id: UUID
    payload: dict
    claim_token: UUID
    attempt_count: int
    lease_expires_at: datetime


_CLAIM_SQL = """
WITH c AS (
  SELECT id FROM geometry_jobs
  WHERE job_type = %(job_type)s AND status IN ('PENDING','RETRY_WAIT')
    AND available_at <= clock_timestamp()
  ORDER BY priority DESC, available_at ASC, created_at ASC, id ASC
  FOR UPDATE SKIP LOCKED LIMIT 1
)
UPDATE geometry_jobs j
SET status = 'RUNNING', attempt_count = attempt_count + 1, claim_token = gen_random_uuid(),
    worker_id = %(worker_id)s,
    lease_expires_at = clock_timestamp() + make_interval(secs => %(lease_seconds)s),
    started_at = COALESCE(started_at, clock_timestamp())
FROM c WHERE j.id = c.id
RETURNING
  j.id, j.owner_id, j.subject_id, j.payload, j.claim_token, j.attempt_count, j.lease_expires_at
"""


def claim_job(
    conn: psycopg.Connection, job_type: str, worker_id: str, lease_seconds: int
) -> ClaimedJob | None:
    """Claims the next eligible job via `FOR UPDATE SKIP LOCKED`, or `None` if empty."""
    with conn.cursor() as cur:
        cur.execute(
            _CLAIM_SQL,
            {"job_type": job_type, "worker_id": worker_id, "lease_seconds": lease_seconds},
        )
        row = cur.fetchone()
        conn.commit()
        if row is None:
            return None
        job_id, owner_id, subject_id, payload, claim_token, attempt_count, lease_expires_at = row
        payload_dict = payload if isinstance(payload, dict) else json.loads(payload)
        return ClaimedJob(
            job_id, owner_id, subject_id, payload_dict, claim_token, attempt_count, lease_expires_at
        )

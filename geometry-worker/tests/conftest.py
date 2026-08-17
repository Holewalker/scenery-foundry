"""Shared fixtures for real-Postgres integration tests (no mocks, matching project convention).

Ryuk (testcontainers' cleanup sidecar) cannot map its port in this sandboxed Docker setup, so it
is disabled here; containers still stop via the context manager's own teardown.
"""

import os

os.environ.setdefault("TESTCONTAINERS_RYUK_DISABLED", "true")

import json
import uuid

import psycopg
import pytest
from testcontainers.postgres import PostgresContainer

# Minimal slice of V5__assets_and_geometry_jobs.sql needed to exercise claim/finalize SQL against a
# real queue table; the full asset/scene schema is exhaustively covered by the backend's own
# Testcontainers suite (PR1-PR4) and is out of scope for the worker's own tests.
GEOMETRY_JOBS_DDL = """
CREATE TABLE users (id uuid PRIMARY KEY);
CREATE TABLE geometry_jobs (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id),
    job_type varchar(32) NOT NULL CHECK (job_type IN ('ASSET_PROCESSING')),
    subject_id uuid NOT NULL,
    status varchar(16) NOT NULL
        CHECK (status IN ('PENDING','RUNNING','RETRY_WAIT','COMPLETED','FAILED')),
    priority int NOT NULL DEFAULT 0,
    attempt_count int NOT NULL DEFAULT 0,
    max_attempts int NOT NULL DEFAULT 3,
    available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    claim_token uuid,
    worker_id text,
    lease_expires_at timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    projected_at timestamptz,
    payload jsonb NOT NULL,
    output_storage_key text,
    output_sha256 varchar(64),
    diagnostics jsonb,
    error_code varchar(64),
    error_message text,
    idempotency_key text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (owner_id, job_type, idempotency_key)
);
"""


@pytest.fixture(scope="session")
def postgres_dsn() -> str:
    try:
        container = PostgresContainer("postgres:18.4", driver=None)
        container.start()
    except Exception as error:  # pragma: no cover - environment guard, not production logic
        pytest.skip(f"Docker/Postgres unavailable for integration tests: {error}")
    dsn = container.get_connection_url()
    with psycopg.connect(dsn) as conn, conn.cursor() as cur:
        cur.execute(GEOMETRY_JOBS_DDL)
        conn.commit()
    yield dsn
    container.stop()


@pytest.fixture
def db_connection(postgres_dsn: str):
    with psycopg.connect(postgres_dsn) as conn:
        yield conn
        conn.rollback()
        with conn.cursor() as cur:
            cur.execute("TRUNCATE geometry_jobs, users CASCADE")
        conn.commit()


def insert_owner(conn) -> uuid.UUID:
    owner_id = uuid.uuid4()
    with conn.cursor() as cur:
        cur.execute("INSERT INTO users (id) VALUES (%s)", [owner_id])
    conn.commit()
    return owner_id


def insert_job(
    conn,
    owner_id: uuid.UUID,
    *,
    status: str = "PENDING",
    priority: int = 0,
    subject_id: uuid.UUID | None = None,
    storage_key: str = "assets/x/original.stl",
    sha256: str = "a" * 64,
    output_directory: str | None = None,
) -> uuid.UUID:
    job_id = uuid.uuid4()
    subject_id = subject_id or uuid.uuid4()
    payload = json.dumps(
        {
            "contract": "scenery-foundry.geometry-job",
            "version": 1,
            "jobType": "ASSET_PROCESSING",
            "jobId": str(job_id),
            "subjectId": str(subject_id),
            "input": {"storageKey": storage_key, "sha256": sha256, "sizeBytes": 10},
            "output": {"directory": output_directory or f"assets/{subject_id}"},
            "options": {"geometryPolicyVersion": 1},
        }
    )
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO geometry_jobs "
            "(id, owner_id, job_type, subject_id, status, priority, payload, idempotency_key) "
            "VALUES (%s, %s, 'ASSET_PROCESSING', %s, %s, %s, %s::jsonb, %s)",
            [job_id, owner_id, subject_id, status, priority, payload, str(job_id)],
        )
    conn.commit()
    return job_id

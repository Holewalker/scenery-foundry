import hashlib

import pytest
from conftest import insert_job, insert_owner

from scenery_foundry_worker.claim import (
    ChecksumMismatchError,
    claim_job,
    sha256_hex,
    verify_checksum,
)


def test_verify_checksum_accepts_a_file_matching_its_expected_sha256(tmp_path):
    content = b"solid cube ... fake stl bytes for a checksum test\n"
    path = tmp_path / "original.stl"
    path.write_bytes(content)

    verify_checksum(path, hashlib.sha256(content).hexdigest())  # must not raise


def test_verify_checksum_rejects_tampered_bytes_before_any_parsing(tmp_path):
    path = tmp_path / "original.stl"
    path.write_bytes(b"tampered bytes that do not match the recorded checksum")

    with pytest.raises(ChecksumMismatchError):
        verify_checksum(path, hashlib.sha256(b"the original untampered bytes").hexdigest())


def test_sha256_hex_matches_the_standard_library_digest():
    data = b"arbitrary payload"

    assert sha256_hex(data) == hashlib.sha256(data).hexdigest()


def test_claim_job_claims_the_only_eligible_pending_job_and_sets_running_fields(db_connection):
    owner_id = insert_owner(db_connection)
    job_id = insert_job(db_connection, owner_id)

    claimed = claim_job(db_connection, "ASSET_PROCESSING", "worker-1", lease_seconds=120)

    assert claimed is not None
    assert claimed.id == job_id
    assert claimed.owner_id == owner_id
    assert claimed.claim_token is not None
    assert claimed.attempt_count == 1
    assert claimed.payload["jobType"] == "ASSET_PROCESSING"


def test_claim_job_returns_none_when_no_eligible_job_exists(db_connection):
    owner_id = insert_owner(db_connection)
    insert_job(db_connection, owner_id, status="RUNNING")

    claimed = claim_job(db_connection, "ASSET_PROCESSING", "worker-1", lease_seconds=120)

    assert claimed is None

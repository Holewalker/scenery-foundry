import json
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import trimesh
from conftest import insert_job, insert_owner

from scenery_foundry_worker.claim import ClaimedJob, sha256_hex
from scenery_foundry_worker.pipeline import PipelineResult, finalize_job, process_job


def _job_for(
    payload_input: dict, *, owner_id=None, subject_id=None, output_directory=None
) -> ClaimedJob:
    subject_id = subject_id or uuid4()
    return ClaimedJob(
        id=uuid4(),
        owner_id=owner_id or uuid4(),
        subject_id=subject_id,
        payload={
            "contract": "scenery-foundry.geometry-job",
            "version": 1,
            "jobType": "ASSET_PROCESSING",
            "jobId": str(uuid4()),
            "subjectId": str(subject_id),
            "input": payload_input,
            "output": {"directory": output_directory or f"assets/{subject_id}"},
            "options": {"geometryPolicyVersion": 1},
        },
        claim_token=uuid4(),
        attempt_count=1,
        lease_expires_at=datetime.now(UTC) + timedelta(seconds=120),
    )


def _write_stl(data_root, storage_key: str, content: bytes) -> str:
    path = data_root / storage_key
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return sha256_hex(content)


def test_process_job_succeeds_for_a_watertight_stl_and_reports_the_projector_contract(tmp_path):
    stl_bytes = trimesh.creation.box(extents=[10, 20, 30]).export(file_type="stl")
    sha256 = _write_stl(tmp_path, "assets/asset-1/original.stl", stl_bytes)
    job = _job_for(
        {
            "storageKey": "assets/asset-1/original.stl",
            "sha256": sha256,
            "sizeBytes": len(stl_bytes),
        },
        output_directory="assets/asset-1",
    )

    result = process_job(job, tmp_path)

    assert result.status == "COMPLETED"
    assert result.output_storage_key == "assets/asset-1/preview.glb"
    assert (tmp_path / "assets" / "asset-1" / "preview.glb").exists()
    diagnostics = json.loads(result.diagnostics_json)
    assert diagnostics["geometryStatus"] == "VALID_VOLUME"
    assert diagnostics["triangleCount"] == 12


def test_process_job_leaves_original_untouched_and_publishes_nothing_for_a_corrupt_stl(tmp_path):
    garbage = b"solid garbage\nnot a real ascii stl body at all\nendsolid garbage\n"
    sha256 = _write_stl(tmp_path, "assets/asset-2/original.stl", garbage)
    job = _job_for(
        {
            "storageKey": "assets/asset-2/original.stl",
            "sha256": sha256,
            "sizeBytes": len(garbage),
        },
        output_directory="assets/asset-2",
    )

    result = process_job(job, tmp_path)

    assert result.status == "FAILED"
    assert result.error_code == "UNPARSEABLE_MESH"
    assert (tmp_path / "assets" / "asset-2" / "original.stl").read_bytes() == garbage
    assert not (tmp_path / "assets" / "asset-2" / "preview.glb").exists()
    assert not (tmp_path / "tmp").exists()


def test_process_job_fails_fast_on_a_checksum_mismatch_before_parsing(tmp_path):
    stl_bytes = trimesh.creation.box(extents=[5, 5, 5]).export(file_type="stl")
    _write_stl(tmp_path, "assets/asset-3/original.stl", stl_bytes)
    job = _job_for(
        {
            "storageKey": "assets/asset-3/original.stl",
            "sha256": "0" * 64,
            "sizeBytes": len(stl_bytes),
        },
        output_directory="assets/asset-3",
    )

    result = process_job(job, tmp_path)

    assert result.status == "FAILED"
    assert result.error_code == "CHECKSUM_MISMATCH"


def test_finalize_job_writes_completed_row_via_the_conditional_claim_token_update(db_connection):
    owner_id = insert_owner(db_connection)
    job_id = insert_job(db_connection, owner_id, status="RUNNING")
    with db_connection.cursor() as cur:
        cur.execute(
            "UPDATE geometry_jobs SET claim_token = gen_random_uuid(), "
            "lease_expires_at = clock_timestamp() + interval '2 minutes' "
            "WHERE id = %s RETURNING claim_token",
            [job_id],
        )
        claim_token = cur.fetchone()[0]
    db_connection.commit()
    job = ClaimedJob(job_id, owner_id, uuid4(), {}, claim_token, 1, datetime.now(UTC))
    diagnostics_json = json.dumps({"geometryStatus": "VALID_VOLUME", "triangleCount": 12})
    result = PipelineResult(
        "COMPLETED",
        diagnostics_json,
        output_storage_key="assets/x/preview.glb",
        output_sha256="c" * 64,
    )

    finalized = finalize_job(db_connection, job, result)

    assert finalized is True
    with db_connection.cursor() as cur:
        cur.execute(
            "SELECT status, output_storage_key, diagnostics->>'geometryStatus' "
            "FROM geometry_jobs WHERE id = %s",
            [job_id],
        )
        row = cur.fetchone()
    assert row == ("COMPLETED", "assets/x/preview.glb", "VALID_VOLUME")

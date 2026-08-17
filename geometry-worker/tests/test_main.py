import trimesh
from conftest import insert_job, insert_owner

from scenery_foundry_worker.claim import sha256_hex
from scenery_foundry_worker.main import run_once


def test_run_once_claims_processes_and_finalizes_the_only_eligible_job(db_connection, tmp_path):
    owner_id = insert_owner(db_connection)
    stl_bytes = trimesh.creation.box(extents=[10, 10, 10]).export(file_type="stl")
    sha256 = sha256_hex(stl_bytes)
    (tmp_path / "assets" / "asset-1").mkdir(parents=True)
    (tmp_path / "assets" / "asset-1" / "original.stl").write_bytes(stl_bytes)
    job_id = insert_job(
        db_connection,
        owner_id,
        storage_key="assets/asset-1/original.stl",
        sha256=sha256,
        output_directory="assets/asset-1",
    )

    handled = run_once(db_connection, tmp_path, worker_id="worker-test")

    assert handled is True
    with db_connection.cursor() as cur:
        cur.execute("SELECT status, output_storage_key FROM geometry_jobs WHERE id = %s", [job_id])
        row = cur.fetchone()
    assert row == ("COMPLETED", "assets/asset-1/preview.glb")


def test_run_once_returns_false_when_the_queue_is_empty(db_connection, tmp_path):
    handled = run_once(db_connection, tmp_path, worker_id="worker-test")

    assert handled is False

from unittest.mock import patch

import pytest
import trimesh
from conftest import insert_job, insert_owner

from scenery_foundry_worker.claim import sha256_hex
from scenery_foundry_worker.main import main, run_once


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


def test_run_once_marks_the_job_failed_and_returns_true_when_process_job_raises(db_connection, tmp_path):
    owner_id = insert_owner(db_connection)
    job_id = insert_job(db_connection, owner_id)

    with patch("scenery_foundry_worker.main.process_job", side_effect=RuntimeError("boom")):
        handled = run_once(db_connection, tmp_path, worker_id="worker-test")

    assert handled is True
    with db_connection.cursor() as cur:
        cur.execute("SELECT status, error_code FROM geometry_jobs WHERE id = %s", [job_id])
        row = cur.fetchone()
    assert row == ("FAILED", "PROCESSING_ERROR")


def test_run_once_still_processes_the_next_job_after_a_prior_crash(db_connection, tmp_path):
    owner_id = insert_owner(db_connection)
    crashing_job_id = insert_job(db_connection, owner_id, priority=1)
    stl_bytes = trimesh.creation.box(extents=[10, 10, 10]).export(file_type="stl")
    sha256 = sha256_hex(stl_bytes)
    (tmp_path / "assets" / "asset-2").mkdir(parents=True)
    (tmp_path / "assets" / "asset-2" / "original.stl").write_bytes(stl_bytes)
    healthy_job_id = insert_job(
        db_connection,
        owner_id,
        priority=0,
        storage_key="assets/asset-2/original.stl",
        sha256=sha256,
        output_directory="assets/asset-2",
    )

    with patch("scenery_foundry_worker.main.process_job", side_effect=RuntimeError("boom")):
        first_handled = run_once(db_connection, tmp_path, worker_id="worker-test")
    second_handled = run_once(db_connection, tmp_path, worker_id="worker-test")

    assert first_handled is True
    assert second_handled is True
    with db_connection.cursor() as cur:
        cur.execute("SELECT status FROM geometry_jobs WHERE id = %s", [crashing_job_id])
        assert cur.fetchone() == ("FAILED",)
        cur.execute("SELECT status FROM geometry_jobs WHERE id = %s", [healthy_job_id])
        assert cur.fetchone() == ("COMPLETED",)


def test_main_exits_immediately_when_worker_database_url_is_missing_before_installing_signal_handlers(
    monkeypatch,
):
    monkeypatch.delenv("WORKER_DATABASE_URL", raising=False)

    with patch("scenery_foundry_worker.main.signal.signal") as mock_signal:
        with pytest.raises(SystemExit):
            main()

    mock_signal.assert_not_called()

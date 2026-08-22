import io
import json
from unittest.mock import patch
from uuid import uuid4

import pytest
import trimesh
from conftest import insert_combined_export_job, insert_job, insert_owner

from scenery_foundry_worker.claim import sha256_hex
from scenery_foundry_worker.logging_setup import configure_logging
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


_IDENTITY_MATRIX = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]


def _snapshot_object(scene_object_id: int, storage_key: str, sha256: str) -> dict:
    return {
        "scene_object_id": scene_object_id,
        "asset_id": str(uuid4()),
        "original_storage_key": storage_key,
        "original_sha256": sha256,
        "matrix_contract_version": 1,
        "matrix_world_column_major": _IDENTITY_MATRIX,
    }


def _write_snapshot(tmp_path, export_id, objects: list[dict]) -> bytes:
    snapshot = {"snapshot_version": 1, "export_id": str(export_id), "objects": objects}
    snapshot_bytes = json.dumps(snapshot).encode("utf-8")
    (tmp_path / "exports" / str(export_id)).mkdir(parents=True)
    (tmp_path / "exports" / str(export_id) / "snapshot.json").write_bytes(snapshot_bytes)
    return snapshot_bytes


def _seed_combined_export_job(db_connection, tmp_path, *, piece_b_open: bool = False):
    """Seeds a real Postgres `geometry_jobs` COMBINED_EXPORT row plus its snapshot + piece STLs.
    `piece_b_open` swaps piece 2 for an open (non-watertight) box, to exercise task 6.5's
    all-or-nothing re-eligibility failure. Returns `(job_id, export_id)`."""
    owner_id = insert_owner(db_connection)
    export_id = uuid4()
    box_a = trimesh.creation.box(extents=[10, 10, 10]).export(file_type="stl")
    template = trimesh.creation.box(extents=[10, 10, 10])
    if piece_b_open:
        open_box = trimesh.Trimesh(
            vertices=template.vertices, faces=template.faces[:-2], process=False
        )
        box_b = open_box.export(file_type="stl")
    else:
        template.apply_translation((5.0, 0.0, 0.0))
        box_b = template.export(file_type="stl")
    (tmp_path / "assets" / "a").mkdir(parents=True)
    (tmp_path / "assets" / "b").mkdir(parents=True)
    (tmp_path / "assets" / "a" / "original.stl").write_bytes(box_a)
    (tmp_path / "assets" / "b" / "original.stl").write_bytes(box_b)
    snapshot_bytes = _write_snapshot(
        tmp_path,
        export_id,
        [
            _snapshot_object(1, "assets/a/original.stl", sha256_hex(box_a)),
            _snapshot_object(2, "assets/b/original.stl", sha256_hex(box_b)),
        ],
    )
    job_id = insert_combined_export_job(
        db_connection, owner_id, export_id=export_id, snapshot_sha256=sha256_hex(snapshot_bytes)
    )
    return job_id, export_id


def test_run_once_claims_processes_and_publishes_a_combined_export_union_job(
    db_connection, tmp_path
):
    """Task 6.11: full union job end-to-end against a real seeded `geometry_jobs` row, publishing
    via the existing 'publish, never replace' storage convention to `exports/{id}/combined.stl`.
    """
    job_id, export_id = _seed_combined_export_job(db_connection, tmp_path)

    handled = run_once(db_connection, tmp_path, worker_id="worker-test")

    assert handled is True
    with db_connection.cursor() as cur:
        cur.execute(
            "SELECT status, output_storage_key, diagnostics->>'exportStatus' "
            "FROM geometry_jobs WHERE id = %s",
            [job_id],
        )
        row = cur.fetchone()
    assert row == ("COMPLETED", f"exports/{export_id}/combined.stl", "COMPLETED")
    assert (tmp_path / "exports" / str(export_id) / "combined.stl").exists()


def test_run_once_fails_the_whole_combined_export_job_naming_the_offending_scene_object_id(
    db_connection, tmp_path
):
    """D3 all-or-nothing: one re-eligibility failure (piece 2's mesh is no longer watertight) fails
    the ENTIRE job, no partial/best-effort combined.stl is ever published, and diagnostics name the
    exact offending `sceneObjectId`."""
    job_id, export_id = _seed_combined_export_job(db_connection, tmp_path, piece_b_open=True)

    handled = run_once(db_connection, tmp_path, worker_id="worker-test")

    assert handled is True
    with db_connection.cursor() as cur:
        cur.execute(
            "SELECT status, error_code, diagnostics->'details'->>'sceneObjectId' "
            "FROM geometry_jobs WHERE id = %s",
            [job_id],
        )
        row = cur.fetchone()
    assert row == ("FAILED", "COMBINED_INPUT_INELIGIBLE", "2")
    assert not (tmp_path / "exports" / str(export_id) / "combined.stl").exists()


def test_claim_next_rotates_the_try_order_by_poll_index():
    """Task 6.2: rotating claim order per poll — an odd `poll_index` tries COMBINED_EXPORT before
    ASSET_PROCESSING, so a saturated asset queue cannot starve exports on this single-worker
    loop."""
    from scenery_foundry_worker.main import JOB_TYPES, _claim_next

    tried: list[str] = []

    def fake_claim_job(_conn, job_type, _worker_id, _lease_seconds):
        tried.append(job_type)
        return None

    with patch("scenery_foundry_worker.main.claim_job", side_effect=fake_claim_job):
        _claim_next(None, "worker-test", 120, poll_index=0)
        _claim_next(None, "worker-test", 120, poll_index=1)

    assert tried == [*JOB_TYPES, *reversed(JOB_TYPES)]


def test_run_once_logs_a_single_structured_json_line_when_process_job_raises_no_raw_traceback_dump(
    db_connection, tmp_path
):
    """Task 3.5: `traceback.print_exc()` is replaced by structured logging (design's platform-
    observability spec: "no raw stdout traceback dump", job_id/asset_id/error detail present)."""
    owner_id = insert_owner(db_connection)
    subject_id = uuid4()
    job_id = insert_job(db_connection, owner_id, subject_id=subject_id)
    stream = io.StringIO()
    configure_logging(stream=stream)

    with patch("scenery_foundry_worker.main.process_job", side_effect=RuntimeError("boom")):
        handled = run_once(db_connection, tmp_path, worker_id="worker-test")

    assert handled is True
    lines = [line for line in stream.getvalue().splitlines() if line.strip()]
    assert len(lines) == 1, f"expected exactly one structured JSON line, got: {lines!r}"
    payload = json.loads(lines[0])  # would raise if it were a raw multi-line traceback dump
    assert payload["jobId"] == str(job_id)
    assert payload["jobType"] == "ASSET_PROCESSING"
    assert payload["subjectId"] == str(subject_id)
    assert payload["workerId"] == "worker-test"
    assert payload["error.type"] == "RuntimeError"
    assert payload["error.message"] == "boom"
    assert "Traceback" in payload["error.stackTrace"]


def test_poll_cycle_touches_the_liveness_marker_whether_or_not_a_job_was_claimed(tmp_path):
    """Task 3.5: the liveness marker is touched every poll cycle (D9), not only when a job is
    claimed — an idle worker must still be reported healthy."""
    from scenery_foundry_worker.main import _poll_cycle

    marker = tmp_path / "liveness"
    with patch("scenery_foundry_worker.main.liveness_marker_path", return_value=marker):
        with patch("scenery_foundry_worker.main.run_once", return_value=False) as mock_run_once:
            handled = _poll_cycle(
                conn=None, data_root=tmp_path, worker_id="w", lease_seconds=120, poll_index=0
            )

    assert handled is False
    assert marker.exists()
    mock_run_once.assert_called_once_with(None, tmp_path, "w", 120, 0)


def test_main_configures_structured_logging_and_emits_a_startup_line(monkeypatch, tmp_path):
    """Task 3.5: `main()` installs the JSON formatter and announces startup structurally instead
    of the old raw `print(worker_identity(), flush=True)`."""
    monkeypatch.setenv("WORKER_DATABASE_URL", "postgresql://unused/unused")
    monkeypatch.setenv("WORKER_LIVENESS_PATH", str(tmp_path / "liveness"))
    stream = io.StringIO()

    class _ImmediatelyStoppedEvent:
        def is_set(self):
            return True

        def wait(self, _seconds):
            return None

    fake_event = _ImmediatelyStoppedEvent()
    with patch("scenery_foundry_worker.main.threading.Event", return_value=fake_event):
        with patch("scenery_foundry_worker.main.psycopg.connect") as mock_connect:
            mock_connect.return_value.__enter__.return_value = object()
            with patch("scenery_foundry_worker.logging_setup.sys.stdout", stream):
                main()

    lines = [line for line in stream.getvalue().splitlines() if line.strip()]
    assert len(lines) >= 1
    payload = json.loads(lines[0])
    assert payload["level"] == "INFO"
    assert payload["service.name"] == "scenery-foundry.geometry-worker"

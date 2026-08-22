import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

import numpy as np
import pytest
import trimesh

from scenery_foundry_worker.claim import ClaimedJob, sha256_hex
from scenery_foundry_worker.combined_export import (
    CombinedExportError,
    _fold_union,
    _validate_merged,
    _verify_piece,
    apply_column_major_transform,
    process_combined_export_job,
)

FIXTURE = Path(__file__).parents[2] / "contracts" / "fixtures" / "transform-v1.json"


def _box_bytes(extents, translation=(0.0, 0.0, 0.0)) -> bytes:
    box = trimesh.creation.box(extents=extents)
    box.apply_translation(translation)
    return box.export(file_type="stl")


def _snapshot_object(scene_object_id: int, storage_key: str, sha256: str, matrix=None) -> dict:
    identity = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
    return {
        "scene_object_id": scene_object_id,
        "asset_id": str(uuid4()),
        "original_storage_key": storage_key,
        "original_sha256": sha256,
        "matrix_contract_version": 1,
        "matrix_world_column_major": matrix or identity,
    }


def _combined_job(export_id, snapshot_key: str, snapshot_sha256: str) -> ClaimedJob:
    return ClaimedJob(
        id=uuid4(),
        owner_id=uuid4(),
        subject_id=export_id,
        payload={
            "contract": "scenery-foundry.geometry-job",
            "version": 1,
            "jobType": "COMBINED_EXPORT",
            "jobId": str(uuid4()),
            "subjectId": str(export_id),
            "input": {"storageKey": snapshot_key, "sha256": snapshot_sha256},
            "output": {"directory": f"exports/{export_id}"},
            "options": {"geometryPolicyVersion": 1, "booleanEngine": "manifold3d"},
        },
        claim_token=uuid4(),
        attempt_count=1,
        lease_expires_at=datetime.now(UTC) + timedelta(seconds=120),
    )


# --- Task 6.3/6.4: column-major -> row-major transform matches the ADR-0001 shared fixture ---


def test_apply_column_major_transform_matches_the_shared_adr0001_fixture() -> None:
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    assert fixture["contract"] == "scenery-foundry.transform"

    for case in fixture["cases"]:
        mesh = trimesh.Trimesh(vertices=[case["point"]], faces=[], process=False)

        apply_column_major_transform(mesh, case["matrixColumnMajor"])

        np.testing.assert_allclose(mesh.vertices[0], case["expectedPoint"], rtol=0, atol=1e-9)


# --- Task 6.5/6.6: per-piece re-verification before union ---


def test_verify_piece_accepts_a_watertight_box_and_applies_its_transform(tmp_path):
    stl_bytes = _box_bytes([10, 10, 10])
    (tmp_path / "assets" / "a").mkdir(parents=True)
    (tmp_path / "assets" / "a" / "original.stl").write_bytes(stl_bytes)
    obj = _snapshot_object(1, "assets/a/original.stl", sha256_hex(stl_bytes))

    piece = _verify_piece(tmp_path, obj)

    assert piece.scene_object_id == 1
    # `piece.mesh` is the raw STL-loaded mesh (process=False, one unshared vertex triple per face,
    # per ADR-0006's loader convention) — watertightness is only meaningful on a welded analysis
    # copy, which `run_geometry_checks` already proved VALID_VOLUME for above.
    assert len(piece.mesh.faces) == 12


def test_verify_piece_rejects_ineligible_pieces_naming_the_scene_object_id(tmp_path):
    """Triangulated: a checksum mismatch AND a mesh that fails ADR-0006 check 5 (is_watertight,
    via an open box missing one face) both resolve to the same all-or-nothing error code, each
    naming its own `sceneObjectId`."""
    good_bytes = _box_bytes([10, 10, 10])
    (tmp_path / "assets" / "b").mkdir(parents=True)
    (tmp_path / "assets" / "b" / "original.stl").write_bytes(good_bytes)
    checksum_mismatch = _snapshot_object(7, "assets/b/original.stl", "0" * 64)

    box = trimesh.creation.box(extents=[10, 10, 10])
    open_box = trimesh.Trimesh(vertices=box.vertices, faces=box.faces[:-2], process=False)
    open_bytes = open_box.export(file_type="stl")
    (tmp_path / "assets" / "c").mkdir(parents=True)
    (tmp_path / "assets" / "c" / "original.stl").write_bytes(open_bytes)
    not_watertight = _snapshot_object(3, "assets/c/original.stl", sha256_hex(open_bytes))

    for obj, expected_id in [(checksum_mismatch, 7), (not_watertight, 3)]:
        with pytest.raises(CombinedExportError) as exc_info:
            _verify_piece(tmp_path, obj)
        assert exc_info.value.error_code == "COMBINED_INPUT_INELIGIBLE"
        assert exc_info.value.details["sceneObjectId"] == expected_id


# --- Task 6.7/6.8: pairwise left-fold union in scene_object_id ASC order ---


def _piece(scene_object_id: int, extents, translation=(0.0, 0.0, 0.0)):
    from scenery_foundry_worker.combined_export import PieceInput

    box = trimesh.creation.box(extents=extents)
    box.apply_translation(translation)
    return PieceInput(scene_object_id, box)


def test_fold_union_of_two_overlapping_boxes_has_the_known_union_volume():
    # Box A: 10x10x10 centered at origin -> [-5,5]^3. Box B: same size translated by +5 on X ->
    # [0,10]x[-5,5]x[-5,5]. Overlap is [0,5]x[-5,5]x[-5,5] = 5*10*10 = 500.
    # Union volume = 1000 + 1000 - 500 = 1500.
    piece_a = _piece(2, [10, 10, 10])
    piece_b = _piece(1, [10, 10, 10], translation=(5.0, 0.0, 0.0))

    merged = _fold_union([piece_a, piece_b])

    assert merged.status().name == "NoError"
    assert merged.volume() == pytest.approx(1500.0, rel=1e-6)


def test_fold_union_fails_naming_both_offending_scene_object_ids_on_a_bad_step(monkeypatch):
    from scenery_foundry_worker import combined_export

    class _FakeStatus:
        name = "NotManifold"

    class _FakeManifold:
        def status(self):
            return _FakeStatus()

        def __add__(self, other):
            return self

    monkeypatch.setattr(combined_export, "_to_manifold", lambda mesh: _FakeManifold())

    piece_a = _piece(4, [10, 10, 10])
    piece_b = _piece(9, [10, 10, 10], translation=(5.0, 0.0, 0.0))

    with pytest.raises(CombinedExportError) as exc_info:
        _fold_union([piece_b, piece_a])

    assert exc_info.value.error_code == "COMBINED_UNION_FAILED"
    assert exc_info.value.details["sceneObjectIds"] == [4, 4]


def test_validate_merged_namespaces_the_temp_path_by_claim_token_not_just_job_id(tmp_path):
    """Codex finding on PR6 (#47): a job reclaimed after its lease expires (ADR-0005) shares the
    same job_id as the original attempt but gets a fresh claim_token. If the temp path were keyed
    by job_id alone, both attempts would race the same file; keying by (job_id, claim_token)
    matches pipeline.py's already-established tmp_glb precedent and isolates them."""
    piece_a = _piece(1, [10, 10, 10])
    piece_b = _piece(2, [10, 10, 10], translation=(5.0, 0.0, 0.0))
    merged_first = _fold_union([piece_a, piece_b])
    merged_second = _fold_union([piece_a, piece_b])

    pieces = [piece_a, piece_b]
    _, first_path = _validate_merged(
        merged_first, tmp_path, job_id="job-1", claim_token="token-original", pieces=pieces
    )
    _, second_path = _validate_merged(
        merged_second, tmp_path, job_id="job-1", claim_token="token-reclaimed", pieces=pieces
    )

    assert first_path != second_path
    assert first_path.exists()
    assert second_path.exists()


def test_validate_merged_export_reload_recheck_succeeds_for_a_watertight_union(tmp_path):
    piece_a = _piece(1, [10, 10, 10])
    piece_b = _piece(2, [10, 10, 10], translation=(5.0, 0.0, 0.0))
    merged = _fold_union([piece_a, piece_b])

    check, stl_path = _validate_merged(
        merged, tmp_path, job_id="job-1", claim_token="token-1", pieces=[piece_a, piece_b]
    )

    assert check.geometry_status == "VALID_VOLUME"
    assert stl_path.exists()
    # The reload re-check (task 6.8) proves the exported STL bytes themselves are valid, not just
    # the in-memory Manifold; watertightness is only meaningful on a welded analysis copy.
    from scenery_foundry_worker.geometry_checks import load_stl_for_analysis, run_geometry_checks

    reloaded_check = run_geometry_checks(load_stl_for_analysis(stl_path))
    assert reloaded_check.geometry_status == "VALID_VOLUME"


# --- Correction: ADR-0006 accumulated triangle ceilings + quantized bounds/volume tolerance ---


def test_validate_merged_fails_output_triangle_limit_exceeded(tmp_path, monkeypatch):
    """ADR-0006 (~line 94): output triangles > 5,000,000 fails `COMBINED_OUTPUT_LIMIT_EXCEEDED`.
    Threshold monkeypatched to 1 (no literal 5M-triangle fixture) so the known union trips it."""
    from scenery_foundry_worker import combined_export

    monkeypatch.setattr(combined_export, "_COMBINED_OUTPUT_TRIANGLE_LIMIT", 1)

    piece_a = _piece(1, [10, 10, 10])
    piece_b = _piece(2, [10, 10, 10], translation=(5.0, 0.0, 0.0))
    merged = _fold_union([piece_a, piece_b])

    with pytest.raises(CombinedExportError) as exc_info:
        _validate_merged(
            merged, tmp_path, job_id="job-2", claim_token="token-2", pieces=[piece_a, piece_b]
        )

    assert exc_info.value.error_code == "COMBINED_OUTPUT_LIMIT_EXCEEDED"


def test_validate_merged_fails_roundtrip_mismatch_when_reloaded_bounds_shift(tmp_path, monkeypatch):
    """ADR-0006 (~lines 59-65): reloaded bounds vs. quantized reference, tolerance
    `max(1e-5mm, 4q)`. A fake reload shifting the mesh 1mm (still watertight/volume>0, so checks
    3-8 pass) proves only this new gate fires, with `COMBINED_ROUNDTRIP_MISMATCH`."""
    from scenery_foundry_worker import combined_export

    piece_a = _piece(1, [10, 10, 10])
    piece_b = _piece(2, [10, 10, 10], translation=(5.0, 0.0, 0.0))
    merged = _fold_union([piece_a, piece_b])

    real_load = combined_export.load_stl_for_analysis

    def _shifted_load(path):
        mesh = real_load(path)
        mesh.apply_translation([1.0, 0.0, 0.0])
        return mesh

    monkeypatch.setattr(combined_export, "load_stl_for_analysis", _shifted_load)

    with pytest.raises(CombinedExportError) as exc_info:
        _validate_merged(
            merged, tmp_path, job_id="job-3", claim_token="token-3", pieces=[piece_a, piece_b]
        )

    assert exc_info.value.error_code == "COMBINED_ROUNDTRIP_MISMATCH"


# --- Task 6.11/6.12: end-to-end union job (pure, no DB — DB integration lives in test_main.py) ---


def _seed_two_piece_export(tmp_path, *, piece_b_sha256: str | None = None):
    """Writes both piece STLs + `snapshot.json` for a standard two-overlapping-box combined export
    and returns `(export_id, job)`. `piece_b_sha256` overrides piece 2's recorded checksum to
    simulate the asset changing since capture (task 6.5's re-eligibility scenario)."""
    export_id = uuid4()
    piece_a_bytes = _box_bytes([10, 10, 10])
    piece_b_bytes = _box_bytes([10, 10, 10], translation=(5.0, 0.0, 0.0))
    (tmp_path / "assets" / "a").mkdir(parents=True)
    (tmp_path / "assets" / "b").mkdir(parents=True)
    (tmp_path / "assets" / "a" / "original.stl").write_bytes(piece_a_bytes)
    (tmp_path / "assets" / "b" / "original.stl").write_bytes(piece_b_bytes)
    snapshot = {
        "snapshot_version": 1,
        "export_id": str(export_id),
        "objects": [
            _snapshot_object(1, "assets/a/original.stl", sha256_hex(piece_a_bytes)),
            _snapshot_object(
                2, "assets/b/original.stl", piece_b_sha256 or sha256_hex(piece_b_bytes)
            ),
        ],
    }
    snapshot_bytes = json.dumps(snapshot).encode("utf-8")
    (tmp_path / "exports" / str(export_id)).mkdir(parents=True)
    (tmp_path / "exports" / str(export_id) / "snapshot.json").write_bytes(snapshot_bytes)
    job = _combined_job(export_id, f"exports/{export_id}/snapshot.json", sha256_hex(snapshot_bytes))
    return export_id, job


def test_process_combined_export_job_completes_and_publishes_combined_stl(tmp_path):
    export_id, job = _seed_two_piece_export(tmp_path)

    result = process_combined_export_job(job, tmp_path)

    assert result.status == "COMPLETED"
    assert result.output_storage_key == f"exports/{export_id}/combined.stl"
    assert (tmp_path / "exports" / str(export_id) / "combined.stl").exists()
    diagnostics = json.loads(result.diagnostics_json)
    assert diagnostics["exportStatus"] == "COMPLETED"
    assert diagnostics["pieceCount"] == 2
    assert "geometryStatus" not in diagnostics


def test_process_combined_export_job_fails_the_whole_job_naming_the_ineligible_piece(tmp_path):
    export_id, job = _seed_two_piece_export(tmp_path, piece_b_sha256="0" * 64)

    result = process_combined_export_job(job, tmp_path)

    assert result.status == "FAILED"
    assert result.error_code == "COMBINED_INPUT_INELIGIBLE"
    diagnostics = json.loads(result.diagnostics_json)
    assert diagnostics["details"]["sceneObjectId"] == 2
    assert not (tmp_path / "exports" / str(export_id) / "combined.stl").exists()


def test_process_combined_export_job_fails_on_accumulated_input_triangle_limit_before_union(
    tmp_path, monkeypatch
):
    """ADR-0006 (~line 93): accumulated input triangles > 5,000,000 fails
    `COMBINED_INPUT_LIMIT_EXCEEDED` BEFORE union runs. Threshold monkeypatched to 20 (24 > 20 for
    two 12-tri boxes); `_fold_union` raises if called, proving this is a pre-union gate."""
    from scenery_foundry_worker import combined_export

    monkeypatch.setattr(combined_export, "_COMBINED_INPUT_TRIANGLE_LIMIT", 20)

    def _must_not_run(*args, **kwargs):
        raise AssertionError("_fold_union must not run once the input triangle ceiling is exceeded")

    monkeypatch.setattr(combined_export, "_fold_union", _must_not_run)

    export_id, job = _seed_two_piece_export(tmp_path)

    result = process_combined_export_job(job, tmp_path)

    assert result.status == "FAILED"
    assert result.error_code == "COMBINED_INPUT_LIMIT_EXCEEDED"
    assert not (tmp_path / "exports" / str(export_id) / "combined.stl").exists()


def test_process_combined_export_job_fails_on_snapshot_checksum_mismatch_before_reading_pieces(
    tmp_path,
):
    export_id = uuid4()
    (tmp_path / "exports" / str(export_id)).mkdir(parents=True)
    (tmp_path / "exports" / str(export_id) / "snapshot.json").write_bytes(b'{"objects":[]}')
    job = _combined_job(export_id, f"exports/{export_id}/snapshot.json", "0" * 64)

    result = process_combined_export_job(job, tmp_path)

    assert result.status == "FAILED"
    assert result.error_code == "CHECKSUM_MISMATCH"

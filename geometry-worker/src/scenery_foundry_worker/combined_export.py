"""Combined Export union pipeline (Phase 4 design D3, ADR-0001 transforms, ADR-0006 Combined
Export v1 checks): fetches the published snapshot, re-verifies + transforms each piece, pairwise
left-folds them through Manifold in `scene_object_id` ASC order, and re-validates the merged
result end-to-end (export -> reload -> re-check) before letting the job COMPLETE. Any pre-union
re-eligibility failure or non-`NoError` union step fails the WHOLE job naming the offending
piece(s) (D3 all-or-nothing) — no partial/best-effort STL is ever produced.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import trimesh
from manifold3d import Error, Manifold, Mesh

from .claim import ChecksumMismatchError, ClaimedJob, sha256_hex, verify_checksum
from .geometry_checks import (
    _WELD_DIGITS,
    GeometryCheckResult,
    UnparseableMeshError,
    load_stl_for_analysis,
    run_geometry_checks,
)
from .job_result import PipelineResult
from .report import build_combined_report, canonical_report_bytes
from .storage import publish_no_replace


class CombinedExportError(Exception):
    """Carries the stable error code + attribution details (D3: name the offending piece(s))."""

    def __init__(self, error_code: str, details: dict | None = None):
        super().__init__(error_code)
        self.error_code = error_code
        self.details = details or {}


@dataclass(frozen=True)
class PieceInput:
    scene_object_id: int
    mesh: trimesh.Trimesh


def apply_column_major_transform(mesh: trimesh.Trimesh, matrix_column_major: list[float]) -> None:
    """ADR-0001: the snapshot's 16-element column-major array becomes trimesh's row-major 4x4 via
    `reshape((4, 4), order="F")` (equivalent to `.reshape((4, 4)).T`) before `apply_transform`.
    Matches `test_transform_contract.py`'s reconstruction of the same shared fixture."""
    matrix = np.asarray(matrix_column_major, dtype=np.float64).reshape((4, 4), order="F")
    mesh.apply_transform(matrix)


def _to_manifold(mesh: trimesh.Trimesh) -> Manifold:
    """Welds a zero-tolerance analysis copy first (same convention as `geometry_checks.py`'s
    ADR-0006 check 8): Manifold needs shared vertex indices to recognize watertightness, but a
    mesh loaded from raw STL bytes (`process=False`) has one unshared vertex triple per face and
    would spuriously report as not manifold without this weld."""
    analysis = mesh.copy()
    analysis.merge_vertices(digits_vertex=_WELD_DIGITS)
    manifold_mesh = Mesh(
        vert_properties=np.asarray(analysis.vertices, dtype=np.float32),
        tri_verts=np.asarray(analysis.faces, dtype=np.uint32),
    )
    return Manifold(manifold_mesh)


def _from_manifold(manifold: Manifold) -> trimesh.Trimesh:
    mesh = manifold.to_mesh()
    vertices = np.asarray(mesh.vert_properties)[:, :3]
    faces = np.asarray(mesh.tri_verts)
    return trimesh.Trimesh(vertices=vertices, faces=faces, process=False)


def _verify_piece(data_root: Path, obj: dict) -> PieceInput:
    """Re-verifies checksum + eligibility for ONE snapshot object before it may join the union
    (task 6.5): a checksum mismatch, unparseable mesh, or a mesh that no longer passes ADR-0006's
    checks (not VALID_VOLUME) fails naming this object's `sceneObjectId` — the state on disk may
    have changed since capture, so pre-union re-verification is authoritative, not the snapshot."""
    scene_object_id = obj["scene_object_id"]
    path = data_root / obj["original_storage_key"]
    try:
        verify_checksum(path, obj["original_sha256"])
        mesh = load_stl_for_analysis(path)
    except (ChecksumMismatchError, UnparseableMeshError) as error:
        raise CombinedExportError(
            "COMBINED_INPUT_INELIGIBLE", {"sceneObjectId": scene_object_id}
        ) from error

    check = run_geometry_checks(mesh)
    if check.geometry_status != "VALID_VOLUME":
        raise CombinedExportError("COMBINED_INPUT_INELIGIBLE", {"sceneObjectId": scene_object_id})

    apply_column_major_transform(mesh, obj["matrix_world_column_major"])
    return PieceInput(scene_object_id, mesh)


def _fold_union(pieces: list[PieceInput]) -> Manifold:
    """Pairwise left-fold in `scene_object_id` ASC order (Phase 4 design decision), not an N-ary
    call: checking `.status()` after every step is what lets a failure name the two exact pieces
    involved in that step, which a single aggregate N-ary/`batch_boolean` result could not."""
    ordered = sorted(pieces, key=lambda piece: piece.scene_object_id)
    acc_id = ordered[0].scene_object_id
    acc = _to_manifold(ordered[0].mesh)
    if acc.status() is not Error.NoError:
        raise CombinedExportError("COMBINED_UNION_FAILED", {"sceneObjectIds": [acc_id, acc_id]})

    for piece in ordered[1:]:
        merged = acc + _to_manifold(piece.mesh)
        if merged.status() is not Error.NoError:
            raise CombinedExportError(
                "COMBINED_UNION_FAILED", {"sceneObjectIds": [acc_id, piece.scene_object_id]}
            )
        acc = merged
        acc_id = piece.scene_object_id

    return acc


def _validate_merged(merged: Manifold, data_root: Path, job_id) -> tuple[GeometryCheckResult, Path]:
    """ADR-0006 Combined Export post-union gate (task 6.8): the merged result must pass checks 3-8,
    then be exported, reloaded from its own STL bytes, and re-checked — proving the exported STL
    itself is valid rather than trusting the in-memory Manifold object blindly (PRD: never mark a
    Combined Export valid without final validation)."""
    merged_mesh = _from_manifold(merged)
    check = run_geometry_checks(merged_mesh)
    if check.geometry_status != "VALID_VOLUME":
        raise CombinedExportError(_error_code_for(check))

    stl_path = data_root / "tmp" / str(job_id) / "combined.stl"
    stl_path.parent.mkdir(parents=True, exist_ok=True)
    stl_path.write_bytes(merged_mesh.export(file_type="stl"))

    reloaded = load_stl_for_analysis(stl_path)
    reload_check = run_geometry_checks(reloaded)
    if reload_check.geometry_status != "VALID_VOLUME":
        raise CombinedExportError(_error_code_for(reload_check))

    return check, stl_path


def _error_code_for(check: GeometryCheckResult) -> str:
    for diagnostic in check.diagnostics:
        if diagnostic.severity == "ERROR":
            return diagnostic.code
    # Defensive fallback; unreachable if geometry_status != VALID_VOLUME.
    return "COMBINED_POST_UNION_INVALID"


def process_combined_export_job(job: ClaimedJob, data_root: Path) -> PipelineResult:
    data_root = Path(data_root)
    input_spec = job.payload["input"]
    snapshot_path = data_root / input_spec["storageKey"]

    try:
        verify_checksum(snapshot_path, input_spec["sha256"])
    except ChecksumMismatchError:
        return _combined_failed("CHECKSUM_MISMATCH", piece_count=0)

    snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
    objects = snapshot["objects"]
    piece_count = len(objects)

    try:
        pieces = [_verify_piece(data_root, obj) for obj in objects]
        merged = _fold_union(pieces)
        check, stl_path = _validate_merged(merged, data_root, job.id)
    except CombinedExportError as error:
        return _combined_failed(error.error_code, piece_count, error.details)

    output_directory = job.payload.get("output", {}).get("directory", f"exports/{job.subject_id}")
    final_key = f"{output_directory}/combined.stl"
    publish_no_replace(stl_path, data_root / final_key)
    output_sha256 = sha256_hex((data_root / final_key).read_bytes())

    report = build_combined_report(check, checksum=output_sha256, piece_count=piece_count)
    diagnostics_json = canonical_report_bytes(report).decode("utf-8")

    return PipelineResult(
        "COMPLETED", diagnostics_json, output_storage_key=final_key, output_sha256=output_sha256
    )


def _combined_failed(
    error_code: str, piece_count: int, details: dict | None = None
) -> PipelineResult:
    diagnostics: dict = {
        "exportStatus": "FAILED",
        "errorCode": error_code,
        "pieceCount": piece_count,
    }
    if details:
        diagnostics["details"] = details
    return PipelineResult("FAILED", json.dumps(diagnostics, sort_keys=True), error_code=error_code)

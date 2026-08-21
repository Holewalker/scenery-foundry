"""Combined Export union pipeline (Phase 4 design D3, ADR-0001 transforms, ADR-0006 Combined
Export v1 checks): fetches the published snapshot, re-verifies + transforms each piece, pairwise
left-folds them through Manifold in `scene_object_id` ASC order, and re-validates the merged
result end-to-end (export -> reload -> re-check) before letting the job COMPLETE. Any pre-union
re-eligibility failure or non-`NoError` union step fails the WHOLE job naming the offending
piece(s) (D3 all-or-nothing) — no partial/best-effort STL is ever produced.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, replace
from pathlib import Path

import numpy as np
import trimesh
from manifold3d import Error, Manifold, Mesh

from .claim import ChecksumMismatchError, ClaimedJob, sha256_hex, verify_checksum
from .geometry_checks import (
    _WELD_DIGITS,
    Diagnostic,
    GeometryCheckResult,
    UnparseableMeshError,
    load_stl_for_analysis,
    run_geometry_checks,
)
from .job_result import PipelineResult
from .report import build_combined_report, canonical_report_bytes
from .storage import publish_no_replace

# ADR-0006 limits table (~line 93-94): accumulated input/output triangle ceilings, separate from
# the per-asset ceiling. Module-level so tests can monkeypatch instead of building 5M-tri fixtures.
_COMBINED_INPUT_TRIANGLE_LIMIT = 5_000_000
_COMBINED_OUTPUT_TRIANGLE_LIMIT = 5_000_000
_EPS64 = 2.0**-52  # ADR-0006 lines 67-71: reload/quantized volume tolerance formula.


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


def _check_accumulated_input_triangle_limit(pieces: list[PieceInput]) -> None:
    """ADR-0006 (~line 93): accumulated triangles across ALL pieces must not exceed 5,000,000 —
    separate from the per-piece ceiling. Runs after `_verify_piece`, before `_fold_union`."""
    total = sum(len(piece.mesh.faces) for piece in pieces)
    if total > _COMBINED_INPUT_TRIANGLE_LIMIT:
        raise CombinedExportError("COMBINED_INPUT_LIMIT_EXCEEDED", {"triangleCount": total})


def _aabb_diagonal(bounds_min, bounds_max) -> float:
    """ADR-0006 (~line 63-64): `sqrt(sum((max_i - min_i) ** 2 for i in xyz))`."""
    deltas = (float(high) - float(low) for low, high in zip(bounds_min, bounds_max))
    return float(math.sqrt(sum(delta**2 for delta in deltas)))


def _quantize_to_float32(mesh: trimesh.Trimesh) -> trimesh.Trimesh:
    """ADR-0006 (~line 59-60): float32-quantized reference — same vertices STL storage keeps,
    rounded once to float32, read back as float64 for comparison math."""
    quantized_vertices = mesh.vertices.astype(np.float32).astype(np.float64)
    return trimesh.Trimesh(vertices=quantized_vertices, faces=mesh.faces, process=False)


def _max_float32_ulp(vertices: np.ndarray) -> float:
    """ADR-0006 (~line 60-61): `q` = max float32 ULP at magnitude `max(1mm, |coordinate|)`."""
    magnitudes = np.maximum(1.0, np.abs(vertices)).astype(np.float32)
    ulps = np.spacing(magnitudes).astype(np.float64)
    finite = np.isfinite(ulps)
    return float(np.max(ulps[finite])) if finite.any() else 0.0


def _tetrahedral_volume_terms(mesh: trimesh.Trimesh) -> np.ndarray:
    """Per-face signed tetrahedron-from-origin term (`dot(v0, cross(v1, v2)) / 6`); summing all
    terms reproduces the mesh volume. ADR-0006's `kappa` sums the ABSOLUTE value of these."""
    triangles = mesh.vertices[mesh.faces]
    v0, v1, v2 = triangles[:, 0, :], triangles[:, 1, :], triangles[:, 2, :]
    return np.einsum("ij,ij->i", v0, np.cross(v1, v2)) / 6.0


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


def _validate_merged(
    merged: Manifold, data_root: Path, job_id, pieces: list[PieceInput]
) -> tuple[GeometryCheckResult, Path]:
    """ADR-0006 Combined Export post-union gate (task 6.8): export -> reload -> re-check checks
    3-8 first (never trust the in-memory Manifold blindly). Correction (this batch) ADDS the
    output triangle ceiling and the float32-quantization bounds/volume tolerance (lines 59-72)."""
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

    if reload_check.triangle_count > _COMBINED_OUTPUT_TRIANGLE_LIMIT:
        raise CombinedExportError(
            "COMBINED_OUTPUT_LIMIT_EXCEEDED", {"triangleCount": reload_check.triangle_count}
        )

    # ADR-0006 lines 59-65: reloaded bounds vs. the float32-quantized reference, tolerance
    # max(1e-5mm, 4q).
    quantized = _quantize_to_float32(merged_mesh)
    quantized_bounds_min, quantized_bounds_max = quantized.bounds
    q = _max_float32_ulp(quantized.vertices)
    bounds_tolerance = max(1e-5, 4.0 * q)
    for axis in range(3):
        if abs(reload_check.bounds_min[axis] - quantized_bounds_min[axis]) > bounds_tolerance:
            raise CombinedExportError(
                "COMBINED_ROUNDTRIP_MISMATCH",
                {"axis": axis, "boundary": "min", "toleranceMm": bounds_tolerance},
            )
        if abs(reload_check.bounds_max[axis] - quantized_bounds_max[axis]) > bounds_tolerance:
            raise CombinedExportError(
                "COMBINED_ROUNDTRIP_MISMATCH",
                {"axis": axis, "boundary": "max", "toleranceMm": bounds_tolerance},
            )

    # ADR-0006 lines 67-71: reloaded volume vs. quantized volume, tolerance
    # max(1e-12, 64*eps64*kappa).
    volume_before = abs(float(merged_mesh.volume))
    volume_quantized = abs(float(quantized.volume))
    volume_reload = abs(float(reload_check.volume_mm3))
    kappa = float(np.sum(np.abs(_tetrahedral_volume_terms(quantized)))) / volume_quantized
    volume_tolerance = max(1e-12, 64.0 * _EPS64 * kappa)
    volume_ratio = abs(volume_reload - volume_quantized) / volume_quantized
    if volume_ratio > volume_tolerance:
        raise CombinedExportError(
            "COMBINED_ROUNDTRIP_MISMATCH",
            {"volumeRatio": volume_ratio, "toleranceRatio": volume_tolerance},
        )

    # ADR-0006 lines 63-65 + 67-68: register (not gate on) AABB diagonals + the inevitable
    # quantization volume effect, as one informational diagnostic.
    input_min = np.min([piece.mesh.bounds[0] for piece in pieces], axis=0)
    input_max = np.max([piece.mesh.bounds[1] for piece in pieces], axis=0)
    inevitable_effect = (
        abs(volume_quantized - volume_before) / volume_before if volume_before else 0.0
    )
    quantization_diagnostic = Diagnostic(
        "COMBINED_QUANTIZATION_EFFECT",
        "INFO",
        1,
        "geometry.combined_quantization_effect",
        {
            "inputAabbDiagonalMm": _aabb_diagonal(input_min, input_max),
            "preQuantizationAabbDiagonalMm": _aabb_diagonal(*merged_mesh.bounds),
            "quantizedAabbDiagonalMm": _aabb_diagonal(quantized_bounds_min, quantized_bounds_max),
            "inevitableVolumeEffectRatio": inevitable_effect,
        },
    )
    check = replace(check, diagnostics=[*check.diagnostics, quantization_diagnostic])

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
        _check_accumulated_input_triangle_limit(pieces)
        merged = _fold_union(pieces)
        check, stl_path = _validate_merged(merged, data_root, job.id, pieces)
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

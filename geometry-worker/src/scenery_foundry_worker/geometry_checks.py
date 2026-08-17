"""ADR-0006 checks 1-8: measurable geometric validity, independent of `processing_status`.

The loader preserves bytes (`process=False`); checks 3-8 run on a separately welded analysis copy
(zero-tolerance merge) so the original mesh handed to `glb.py` is never silently altered.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import manifold3d
import numpy as np
import trimesh
from manifold3d import Error, Manifold, Mesh

GEOMETRY_POLICY_VERSION = 1
# Zero-tolerance vertex welding per ADR-0006 approximated at binary64 precision (15 significant
# digits); trimesh has no bit-exact grouping primitive, so this is the closest available proxy.
_WELD_DIGITS = 15

_MANIFOLD_CODES = {
    "NonFiniteVertex": "MANIFOLD_NON_FINITE_VERTEX",
    "NotManifold": "MANIFOLD_NOT_MANIFOLD",
    "VertexOutOfBounds": "MANIFOLD_VERTEX_OUT_OF_BOUNDS",
    "PropertiesWrongLength": "MANIFOLD_PROPERTIES_WRONG_LENGTH",
    "MissingPositionProperties": "MANIFOLD_MISSING_POSITION_PROPERTIES",
    "MergeVectorsDifferentLengths": "MANIFOLD_MERGE_VECTORS_DIFFERENT_LENGTHS",
    "MergeIndexOutOfBounds": "MANIFOLD_MERGE_INDEX_OUT_OF_BOUNDS",
    "TransformWrongLength": "MANIFOLD_TRANSFORM_WRONG_LENGTH",
    "RunIndexWrongLength": "MANIFOLD_RUN_INDEX_WRONG_LENGTH",
    "FaceIDWrongLength": "MANIFOLD_FACE_ID_WRONG_LENGTH",
    "InvalidConstruction": "MANIFOLD_INVALID_CONSTRUCTION",
    "ResultTooLarge": "MANIFOLD_RESULT_TOO_LARGE",
    "InvalidTangents": "MANIFOLD_INVALID_TANGENTS",
    "Cancelled": "MANIFOLD_CANCELLED",
}


class UnparseableMeshError(ValueError):
    """Raised when trimesh cannot load exactly one non-empty triangular mesh (ADR-0006 check 2)."""


@dataclass(frozen=True)
class Diagnostic:
    code: str
    severity: str  # "ERROR" | "WARNING" | "INFO"
    count: int
    message_key: str
    details: dict = field(default_factory=dict)


@dataclass(frozen=True)
class GeometryCheckResult:
    geometry_status: str  # "VALID_VOLUME" | "INVALID_VOLUME"
    diagnostics: list[Diagnostic]
    triangle_count: int
    component_count: int
    bounds_min: list[float]
    bounds_max: list[float]
    volume_mm3: float


def load_stl_for_analysis(path: Path) -> trimesh.Trimesh:
    """Loads the original mesh with automatic processing disabled, preserving bytes/vertex order."""
    loaded = trimesh.load(str(path), file_type="stl", process=False)
    if not isinstance(loaded, trimesh.Trimesh) or len(loaded.faces) == 0:
        raise UnparseableMeshError(str(path))
    return loaded


def run_geometry_checks(original: trimesh.Trimesh) -> GeometryCheckResult:
    analysis = original.copy()
    analysis.merge_vertices(digits_vertex=_WELD_DIGITS)

    diagnostics: list[Diagnostic] = []
    if not analysis.is_watertight:
        diagnostics.append(Diagnostic("NOT_WATERTIGHT", "ERROR", 1, "geometry.not_watertight"))
    if not analysis.is_winding_consistent or not analysis.is_volume:
        diagnostics.append(
            Diagnostic("INCONSISTENT_WINDING", "ERROR", 1, "geometry.inconsistent_winding")
        )
    if analysis.volume <= 0:
        diagnostics.append(
            Diagnostic("NON_POSITIVE_VOLUME", "ERROR", 1, "geometry.non_positive_volume")
        )

    manifold_status = _manifold_status(analysis)
    if manifold_status is not Error.NoError:
        code = _manifold_diagnostic_code(manifold_status)
        diagnostics.append(Diagnostic(code, "ERROR", 1, "geometry.manifold_rejected"))

    component_count = _connected_face_component_count(analysis)
    if component_count > 1:
        diagnostics.append(
            Diagnostic(
                "DISCONNECTED_COMPONENTS",
                "INFO",
                component_count,
                "geometry.disconnected_components",
            )
        )

    has_error = any(d.severity == "ERROR" for d in diagnostics)
    geometry_status = "INVALID_VOLUME" if has_error else "VALID_VOLUME"

    return GeometryCheckResult(
        geometry_status=geometry_status,
        diagnostics=diagnostics,
        triangle_count=int(len(original.faces)),
        component_count=component_count,
        bounds_min=[float(value) for value in original.bounds[0]],
        bounds_max=[float(value) for value in original.bounds[1]],
        volume_mm3=float(analysis.volume),
    )


def _connected_face_component_count(analysis: trimesh.Trimesh) -> int:
    """Union-find over `face_adjacency` (no scipy/networkx dependency, unlike `trimesh.split`)."""
    face_count = len(analysis.faces)
    if face_count == 0:
        return 0
    parent = list(range(face_count))

    def find(node: int) -> int:
        while parent[node] != node:
            parent[node] = parent[parent[node]]
            node = parent[node]
        return node

    for left, right in analysis.face_adjacency:
        root_left, root_right = find(int(left)), find(int(right))
        if root_left != root_right:
            parent[root_left] = root_right

    return len({find(node) for node in range(face_count)})


def _manifold_status(analysis: trimesh.Trimesh):
    mesh = Mesh(
        vert_properties=np.asarray(analysis.vertices, dtype=np.float32),
        tri_verts=np.asarray(analysis.faces, dtype=np.uint32),
    )
    return Manifold(mesh).status()


def _manifold_diagnostic_code(status: manifold3d.Error) -> str:
    return _MANIFOLD_CODES.get(status.name, "MANIFOLD_STATUS_UNKNOWN")

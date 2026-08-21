"""ADR-0006 diagnostic report: aggregated, JCS-ordered, and carrying the top-level
`geometryStatus`/`triangleCount` keys PR4's `AssetProjector` reads from `geometry_jobs.diagnostics`.
"""

from __future__ import annotations

import json

import manifold3d
import trimesh

from .geometry_checks import GEOMETRY_POLICY_VERSION, Diagnostic, GeometryCheckResult
from .jcs import canonicalize

_SEVERITY_RANK = {"ERROR": 0, "WARNING": 1, "INFO": 2}


def build_report(result: GeometryCheckResult, original_sha256: str) -> dict:
    return {
        "geometryStatus": result.geometry_status,
        "triangleCount": result.triangle_count,
        "geometryPolicyVersion": GEOMETRY_POLICY_VERSION,
        "libraries": {"trimesh": trimesh.__version__, "manifold3d": _manifold3d_version()},
        "checksum": original_sha256,
        "boundsMin": result.bounds_min,
        "boundsMax": result.bounds_max,
        "volumeMm3": result.volume_mm3,
        "componentCount": result.component_count,
        "diagnostics": _aggregate_and_order(result.diagnostics),
    }


def build_combined_report(
    result: GeometryCheckResult,
    *,
    checksum: str,
    piece_count: int,
    export_status: str = "COMPLETED",
) -> dict:
    """Combined Export diagnostics report (task 6.9/6.10). Deliberately omits the top-level
    `geometryStatus` key: that key is `AssetProjector`'s contract for a single asset, and this
    result is a merged, multi-piece artifact of a different job type (Phase 4 design)."""
    return {
        "exportStatus": export_status,
        "pieceCount": piece_count,
        "triangleCount": result.triangle_count,
        "checksum": checksum,
        "boundsMin": result.bounds_min,
        "boundsMax": result.bounds_max,
        "volumeMm3": result.volume_mm3,
        "diagnostics": _aggregate_and_order(result.diagnostics),
    }


def canonical_report_bytes(report: dict) -> bytes:
    # Defense in depth (ADR-0006/D4): geometry_checks.py already resolves non-finite measurements
    # to `None` before a report reaches here, so this should never fire in practice. `allow_nan=False`
    # makes any future non-finite leak raise here, at the producing boundary, instead of silently
    # emitting a bare NaN/Infinity that only jcs.canonicalize's unrelated constant-rejection catches.
    raw = json.dumps(report, sort_keys=True, allow_nan=False).encode("utf-8")
    return canonicalize(raw).canonical_bytes


def _manifold3d_version() -> str:
    return getattr(manifold3d, "__version__", "3.5.2")


def _aggregate_and_order(diagnostics: list[Diagnostic]) -> list[dict]:
    aggregated: dict[tuple, dict] = {}
    order: dict[tuple, bytes] = {}
    for diagnostic in diagnostics:
        details_raw = json.dumps(diagnostic.details, sort_keys=True).encode("utf-8")
        details_jcs = canonicalize(details_raw).canonical_bytes
        key = (diagnostic.code, diagnostic.severity, details_jcs, diagnostic.message_key)
        if key in aggregated:
            aggregated[key]["count"] += diagnostic.count
        else:
            aggregated[key] = {
                "code": diagnostic.code,
                "severity": diagnostic.severity,
                "count": diagnostic.count,
                "messageKey": diagnostic.message_key,
                "details": diagnostic.details,
            }
            order[key] = details_jcs

    def sort_key(key: tuple):
        entry = aggregated[key]
        return (
            _SEVERITY_RANK[entry["severity"]],
            entry["code"],
            order[key],
            entry["messageKey"],
            entry["count"],
        )

    return [aggregated[key] for key in sorted(aggregated, key=sort_key)]

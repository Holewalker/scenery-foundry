import json

import pytest

from scenery_foundry_worker.geometry_checks import Diagnostic, GeometryCheckResult
from scenery_foundry_worker.jcs import CanonicalizationError
from scenery_foundry_worker.report import build_report, canonical_report_bytes


def _result(diagnostics: list[Diagnostic], status: str = "VALID_VOLUME") -> GeometryCheckResult:
    return GeometryCheckResult(
        geometry_status=status,
        diagnostics=diagnostics,
        triangle_count=12,
        component_count=1,
        bounds_min=[-5.0, -10.0, -15.0],
        bounds_max=[5.0, 10.0, 15.0],
        volume_mm3=6000.0,
    )


def test_report_emits_the_top_level_keys_asset_projector_reads():
    """PR4's AssetProjector reads these two keys at the diagnostics jsonb top level."""
    result = _result([])

    report = build_report(result, original_sha256="a" * 64)

    assert report["geometryStatus"] == "VALID_VOLUME"
    assert report["triangleCount"] == 12


def test_report_includes_adr_0006_metadata_fields():
    result = _result([])

    report = build_report(result, original_sha256="a" * 64)

    assert report["geometryPolicyVersion"] == 1
    assert report["checksum"] == "a" * 64
    assert report["boundsMin"] == [-5.0, -10.0, -15.0]
    assert report["boundsMax"] == [5.0, 10.0, 15.0]
    assert report["volumeMm3"] == 6000.0
    assert report["libraries"]["trimesh"]
    assert report["libraries"]["manifold3d"]


def test_diagnostics_are_ordered_by_severity_then_code():
    result = _result(
        [
            Diagnostic("NOT_WATERTIGHT", "ERROR", 1, "geometry.not_watertight"),
            Diagnostic("DISCONNECTED_COMPONENTS", "INFO", 2, "geometry.disconnected_components"),
            Diagnostic("A_LOWER_CODE_ERROR", "ERROR", 1, "geometry.other"),
        ],
        status="INVALID_VOLUME",
    )

    report = build_report(result, original_sha256="a" * 64)

    codes = [d["code"] for d in report["diagnostics"]]
    assert codes == ["A_LOWER_CODE_ERROR", "NOT_WATERTIGHT", "DISCONNECTED_COMPONENTS"]


def test_duplicate_diagnostics_are_aggregated_by_summing_their_counts():
    result = _result(
        [
            Diagnostic("DISCONNECTED_COMPONENTS", "INFO", 2, "geometry.disconnected_components"),
            Diagnostic("DISCONNECTED_COMPONENTS", "INFO", 3, "geometry.disconnected_components"),
        ]
    )

    report = build_report(result, original_sha256="a" * 64)

    assert len(report["diagnostics"]) == 1
    assert report["diagnostics"][0]["count"] == 5


def test_canonical_report_bytes_round_trips_through_jcs_and_is_deterministic():
    diagnostics = [Diagnostic("NOT_WATERTIGHT", "ERROR", 1, "geometry.not_watertight")]
    result = _result(diagnostics, status="INVALID_VOLUME")
    report = build_report(result, original_sha256="b" * 64)

    first = canonical_report_bytes(report)
    second = canonical_report_bytes(build_report(result, original_sha256="b" * 64))

    assert first == second
    assert json.loads(first.decode("utf-8"))["geometryStatus"] == "INVALID_VOLUME"


def test_canonical_report_bytes_raises_at_the_producing_json_dumps_boundary_not_downstream_in_jcs():
    """Defense in depth (D4): geometry_checks.py already resolves non-finite measurements to `None`
    before they reach here, but a future producer bug must fail loudly at THIS boundary (`json.dumps`
    with `allow_nan=False`) rather than silently emit a bare NaN/Infinity that only gets caught later
    by `jcs.canonicalize`'s unrelated constant-rejection path (`CanonicalizationError`)."""
    result = _result([], status="INVALID_VOLUME")
    report = build_report(result, original_sha256="c" * 64)
    report["volumeMm3"] = float("nan")

    with pytest.raises(ValueError) as exc_info:
        canonical_report_bytes(report)

    assert not isinstance(exc_info.value, CanonicalizationError)

import trimesh

from scenery_foundry_worker.geometry_checks import (
    UnparseableMeshError,
    load_stl_for_analysis,
    run_geometry_checks,
)


def _stl_bytes(mesh: trimesh.Trimesh) -> bytes:
    return mesh.export(file_type="stl")


def _write_stl(tmp_path, mesh: trimesh.Trimesh, name: str = "original.stl"):
    path = tmp_path / name
    path.write_bytes(_stl_bytes(mesh))
    return path


def test_watertight_box_is_reported_valid_volume(tmp_path):
    path = _write_stl(tmp_path, trimesh.creation.box(extents=[10, 20, 30]))
    mesh = load_stl_for_analysis(path)

    result = run_geometry_checks(mesh)

    assert result.geometry_status == "VALID_VOLUME"
    assert result.triangle_count == 12
    assert result.volume_mm3 > 0
    assert all(diagnostic.severity != "ERROR" for diagnostic in result.diagnostics)


def test_non_watertight_mesh_is_invalid_volume_with_not_watertight_diagnostic(tmp_path):
    broken = trimesh.creation.box(extents=[10, 20, 30])
    broken.faces = broken.faces[:-1]
    broken.remove_unreferenced_vertices()
    path = _write_stl(tmp_path, broken)
    mesh = load_stl_for_analysis(path)

    result = run_geometry_checks(mesh)

    assert result.geometry_status == "INVALID_VOLUME"
    assert any(d.code == "NOT_WATERTIGHT" and d.severity == "ERROR" for d in result.diagnostics)


def test_corrupt_stl_raises_unparseable_mesh_error_before_any_check(tmp_path):
    path = tmp_path / "garbage.stl"
    path.write_bytes(b"solid garbage\nthis is not a valid ascii stl body\nendsolid garbage\n")

    try:
        load_stl_for_analysis(path)
        raised = False
    except UnparseableMeshError:
        raised = True

    assert raised


def test_unknown_manifold_status_maps_to_manifold_status_unknown(tmp_path):
    from scenery_foundry_worker.geometry_checks import _manifold_diagnostic_code

    class _FakeStatus:
        name = "SomeFutureManifoldError"

    assert _manifold_diagnostic_code(_FakeStatus()) == "MANIFOLD_STATUS_UNKNOWN"


def test_known_manifold_status_maps_to_its_stable_code():
    from manifold3d import Error

    from scenery_foundry_worker.geometry_checks import _manifold_diagnostic_code

    assert _manifold_diagnostic_code(Error.NotManifold) == "MANIFOLD_NOT_MANIFOLD"


def test_non_finite_volume_resolves_invalid_volume_with_non_finite_measurement_diagnostic(
    monkeypatch, tmp_path
):
    """A NaN volume must not slip past NON_POSITIVE_VOLUME (float('nan') <= 0 is False in Python)
    and must never reach json.dumps/jcs.canonicalize uncaught (ADR-0006, D4)."""
    path = _write_stl(tmp_path, trimesh.creation.box(extents=[10, 20, 30]))
    mesh = load_stl_for_analysis(path)
    monkeypatch.setattr(trimesh.Trimesh, "volume", property(lambda self: float("nan")))

    result = run_geometry_checks(mesh)

    assert result.geometry_status == "INVALID_VOLUME"
    assert any(
        d.code == "NON_FINITE_MEASUREMENT" and d.severity == "ERROR" for d in result.diagnostics
    )
    assert result.volume_mm3 is None
    assert not any(d.code == "NON_POSITIVE_VOLUME" for d in result.diagnostics)


def test_non_finite_bounds_resolves_invalid_volume_with_non_finite_measurement_diagnostic(
    monkeypatch, tmp_path
):
    """Distinct from MANIFOLD_NON_FINITE_VERTEX: finite vertices whose derived bounds overflow."""
    path = _write_stl(tmp_path, trimesh.creation.box(extents=[10, 20, 30]))
    mesh = load_stl_for_analysis(path)
    monkeypatch.setattr(
        trimesh.Trimesh,
        "bounds",
        property(lambda self: [[float("-inf"), -10.0, -15.0], [5.0, 10.0, 15.0]]),
    )

    result = run_geometry_checks(mesh)

    assert result.geometry_status == "INVALID_VOLUME"
    assert any(
        d.code == "NON_FINITE_MEASUREMENT" and d.severity == "ERROR" for d in result.diagnostics
    )
    assert result.bounds_min is None
    assert result.bounds_max == [5.0, 10.0, 15.0]
    assert result.volume_mm3 is not None

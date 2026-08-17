import numpy as np
import trimesh

from scenery_foundry_worker.geometry_checks import load_stl_for_analysis
from scenery_foundry_worker.glb import export_preview_glb, load_glb_first_mesh


def test_glb_export_preserves_exact_source_vertex_and_face_data_no_rescale_no_axis_swap(tmp_path):
    """ADR-0001: mm, right-handed, Y-up; no silent rescale or axis conversion STL -> GLB."""
    source = trimesh.creation.box(extents=[10, 20, 30])
    stl_path = tmp_path / "original.stl"
    stl_path.write_bytes(source.export(file_type="stl"))
    loaded = load_stl_for_analysis(stl_path)

    destination = tmp_path / "tmp" / "job-1" / "token-1" / "preview.glb"
    export_preview_glb(loaded, destination)

    assert destination.exists()
    reloaded = load_glb_first_mesh(destination)
    np.testing.assert_array_equal(reloaded.vertices, loaded.vertices)
    np.testing.assert_array_equal(reloaded.faces, loaded.faces)


def test_glb_export_creates_parent_directories(tmp_path):
    mesh = trimesh.creation.box(extents=[5, 5, 5])
    destination = tmp_path / "deeply" / "nested" / "preview.glb"

    export_preview_glb(mesh, destination)

    assert destination.is_file()
    assert destination.stat().st_size > 0

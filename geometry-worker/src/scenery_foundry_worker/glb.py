"""STL -> GLB export (ADR-0001): mm units, right-handed Y-up, no rescale or axis conversion.

`trimesh.exchange.gltf.export_glb` writes vertex/face data as-is; the round-trip fixture in
`tests/test_glb_roundtrip.py` proves no implicit scale or axis transform is applied.
"""

from __future__ import annotations

from pathlib import Path

import trimesh
from trimesh.exchange.gltf import export_glb


def export_preview_glb(mesh: trimesh.Trimesh, destination: Path) -> None:
    """Exports `mesh` (as loaded, unmerged) to `destination`; creates parent directories."""
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    glb_bytes = export_glb(trimesh.Scene(mesh))
    destination.write_bytes(glb_bytes)


def load_glb_first_mesh(path: Path) -> trimesh.Trimesh:
    """Loads a GLB and returns its first mesh geometry (a GLB always loads as a `Scene`)."""
    loaded = trimesh.load(str(path), file_type="glb")
    return next(iter(loaded.geometry.values()))

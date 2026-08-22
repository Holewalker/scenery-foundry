"""Shared job outcome shape for both the ASSET_PROCESSING and COMBINED_EXPORT finalize paths.

Lives in its own module (not `pipeline.py`) so `combined_export.py` can construct one without a
circular import: `pipeline.py` dispatches to `combined_export.py`, and both need this shape.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class PipelineResult:
    status: str  # "COMPLETED" | "FAILED"
    diagnostics_json: str
    error_code: str | None = None
    output_storage_key: str | None = None
    output_sha256: str | None = None

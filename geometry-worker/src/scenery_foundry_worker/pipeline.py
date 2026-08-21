"""Job-type dispatch (`_HANDLERS`) plus the ASSET_PROCESSING pipeline: checksum -> ADR-0006
checks -> GLB export -> publish -> finalize.

A corrupt/checksum-mismatched input returns FAILED before ever touching the GLB export or publish
steps, so `original.stl` is never modified and no `preview.glb` is written (ADR-0006). An
`INVALID_VOLUME` mesh still finalizes as COMPLETED with a preview — only load/checksum failures fail
the job (ADR-0006's independent `processing_status`/`geometry_status` split).

`process_asset_job` is `process_job`'s pre-PR6 body, moved verbatim (behavior-identical) so
`process_job` can dispatch on `payload["jobType"]` instead of assuming ASSET_PROCESSING (Phase 4
design). `finalize_job` is job-type agnostic already (fenced only by `id` + `claim_token`), so the
COMBINED_EXPORT handler (`combined_export.process_combined_export_job`) reuses it unchanged.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from pathlib import Path

import psycopg

from .claim import ChecksumMismatchError, ClaimedJob, sha256_hex, verify_checksum
from .combined_export import process_combined_export_job
from .geometry_checks import UnparseableMeshError, load_stl_for_analysis, run_geometry_checks
from .glb import export_preview_glb
from .job_result import PipelineResult
from .report import build_report, canonical_report_bytes
from .storage import publish_no_replace

_FINALIZE_COMPLETED_SQL = """
UPDATE geometry_jobs SET status = 'COMPLETED', completed_at = clock_timestamp(),
    output_storage_key = %(key)s, output_sha256 = %(sha256)s, diagnostics = %(diagnostics)s::jsonb
WHERE id = %(id)s AND status = 'RUNNING' AND claim_token = %(token)s
  AND lease_expires_at > clock_timestamp()
"""

_FINALIZE_FAILED_SQL = """
UPDATE geometry_jobs SET status = 'FAILED', completed_at = clock_timestamp(),
    error_code = %(error_code)s, error_message = %(error_message)s,
    diagnostics = %(diagnostics)s::jsonb
WHERE id = %(id)s AND status = 'RUNNING' AND claim_token = %(token)s
  AND lease_expires_at > clock_timestamp()
"""


def process_asset_job(job: ClaimedJob, data_root: Path) -> PipelineResult:
    data_root = Path(data_root)
    input_spec = job.payload["input"]
    original_path = data_root / input_spec["storageKey"]

    try:
        verify_checksum(original_path, input_spec["sha256"])
        mesh = load_stl_for_analysis(original_path)
    except ChecksumMismatchError:
        return _failed("CHECKSUM_MISMATCH")
    except UnparseableMeshError:
        return _failed("UNPARSEABLE_MESH")

    check = run_geometry_checks(mesh)
    report = build_report(check, input_spec["sha256"])
    diagnostics_json = canonical_report_bytes(report).decode("utf-8")

    tmp_glb = data_root / "tmp" / str(job.id) / str(job.claim_token) / "preview.glb"
    export_preview_glb(mesh, tmp_glb)

    directory_default = f"assets/{job.subject_id}"
    output_directory = job.payload.get("output", {}).get("directory", directory_default)
    final_key = f"{output_directory}/preview.glb"
    publish_no_replace(tmp_glb, data_root / final_key)
    output_sha256 = sha256_hex((data_root / final_key).read_bytes())

    return PipelineResult(
        "COMPLETED", diagnostics_json, output_storage_key=final_key, output_sha256=output_sha256
    )


_HANDLERS: dict[str, Callable[[ClaimedJob, Path], PipelineResult]] = {
    "ASSET_PROCESSING": process_asset_job,
    "COMBINED_EXPORT": process_combined_export_job,
}


def process_job(job: ClaimedJob, data_root: Path) -> PipelineResult:
    """Dispatches on `payload["jobType"]` (Phase 4 design). An unknown/missing job type never
    retries (ADR-0002:61-64): `UNSUPPORTED_PAYLOAD_VERSION` is a permanent, non-transient
    failure."""
    handler = _HANDLERS.get(job.payload.get("jobType"))
    if handler is None:
        return _failed("UNSUPPORTED_PAYLOAD_VERSION")
    return handler(job, data_root)


def finalize_job(conn: psycopg.Connection, job: ClaimedJob, result: PipelineResult) -> bool:
    """Conditional finalize fenced by `id + status='RUNNING' + claim_token` (ADR-0005).

    Zero rows affected means the lease was lost (expired or reclaimed) and this attempt is
    abandoned without modifying the job. Job-type agnostic: both ASSET_PROCESSING and
    COMBINED_EXPORT results are written through this exact same statement.
    """
    params = {"id": job.id, "token": job.claim_token, "diagnostics": result.diagnostics_json}
    with conn.cursor() as cur:
        if result.status == "COMPLETED":
            cur.execute(
                _FINALIZE_COMPLETED_SQL,
                {**params, "key": result.output_storage_key, "sha256": result.output_sha256},
            )
        else:
            cur.execute(
                _FINALIZE_FAILED_SQL,
                {**params, "error_code": result.error_code, "error_message": result.error_code},
            )
        rows = cur.rowcount
        conn.commit()
        return rows == 1


def _failed(error_code: str) -> PipelineResult:
    diagnostics_json = json.dumps({"geometryStatus": "UNKNOWN", "errorCode": error_code})
    return PipelineResult("FAILED", diagnostics_json, error_code=error_code)

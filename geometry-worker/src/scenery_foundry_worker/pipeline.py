"""End-to-end job orchestration: checksum -> ADR-0006 checks -> GLB export -> publish -> finalize.

A corrupt/checksum-mismatched input returns FAILED before ever touching the GLB export or publish
steps, so `original.stl` is never modified and no `preview.glb` is written (ADR-0006). An
`INVALID_VOLUME` mesh still finalizes as COMPLETED with a preview — only load/checksum failures fail
the job (ADR-0006's independent `processing_status`/`geometry_status` split).
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import psycopg

from .claim import ChecksumMismatchError, ClaimedJob, sha256_hex, verify_checksum
from .geometry_checks import UnparseableMeshError, load_stl_for_analysis, run_geometry_checks
from .glb import export_preview_glb
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


@dataclass(frozen=True)
class PipelineResult:
    status: str  # "COMPLETED" | "FAILED"
    diagnostics_json: str
    error_code: str | None = None
    output_storage_key: str | None = None
    output_sha256: str | None = None


def process_job(job: ClaimedJob, data_root: Path) -> PipelineResult:
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


def finalize_job(conn: psycopg.Connection, job: ClaimedJob, result: PipelineResult) -> bool:
    """Conditional finalize fenced by `id + status='RUNNING' + claim_token` (ADR-0005).

    Zero rows affected means the lease was lost (expired or reclaimed) and this attempt is
    abandoned without modifying the job.
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

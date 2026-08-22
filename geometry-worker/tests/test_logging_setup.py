"""Task 3.1: JSON formatter fields + redaction (ADR-0008 observability, design "Log record" table).

No mocks of the formatter itself needed (no external I/O); uses a real `logging.Logger` against
an in-memory `io.StringIO` stream so the assertions exercise the exact code path `main.py` uses.
"""

from __future__ import annotations

import io
import json
import logging

from scenery_foundry_worker.logging_setup import bind, configure_logging


def _log_one(
    logger_name: str = "scenery_foundry_worker.test", **context
) -> tuple[dict, io.StringIO]:
    stream = io.StringIO()
    configure_logging(stream=stream)
    logger = logging.getLogger(logger_name)
    if context:
        bind(logger, **context).info("test message")
    else:
        logger.info("test message")
    lines = [line for line in stream.getvalue().splitlines() if line.strip()]
    assert len(lines) == 1, f"expected exactly one JSON line, got: {lines!r}"
    return json.loads(lines[0]), stream


def test_required_fields_are_present():
    payload, _ = _log_one()

    assert payload["level"] == "INFO"
    assert payload["logger"] == "scenery_foundry_worker.test"
    assert payload["message"] == "test message"
    assert payload["service.name"] == "scenery-foundry.geometry-worker"
    # ts must be a real ISO-8601 UTC timestamp, not a placeholder.
    from datetime import datetime

    parsed = datetime.fromisoformat(payload["ts"])
    assert parsed.tzinfo is not None


def test_bound_context_fields_correlate_across_calls():
    payload, _ = _log_one(jobId="job-123", jobType="ASSET_PROCESSING", subjectId="subject-456")

    assert payload["jobId"] == "job-123"
    assert payload["jobType"] == "ASSET_PROCESSING"
    assert payload["subjectId"] == "subject-456"


def test_redacts_password_and_password_hash_fields():
    payload, _ = _log_one(password="hunter2", passwordHash="$2b$10$abcdef")

    assert payload["password"] == "[REDACTED]"
    assert payload["passwordHash"] == "[REDACTED]"


def test_redacts_email_field():
    payload, _ = _log_one(email="user@example.com")

    assert payload["email"] == "[REDACTED]"


def test_redacts_session_id_field():
    payload, _ = _log_one(sessionId="abc123sessiontoken")

    assert payload["sessionId"] == "[REDACTED]"


def test_redacts_csrf_token_field():
    payload, _ = _log_one(csrfToken="csrf-secret-value")

    assert payload["csrfToken"] == "[REDACTED]"


def test_redacts_duckdns_token_field():
    payload, _ = _log_one(DUCKDNS_TOKEN="duckdns-secret-value")

    assert payload["DUCKDNS_TOKEN"] == "[REDACTED]"


def test_redacts_db_password_field():
    payload, _ = _log_one(dbPassword="postgres-secret")

    assert payload["dbPassword"] == "[REDACTED]"


def test_does_not_redact_claim_token_field():
    """`claimToken` is an ADR-0005 lease-fencing UUID, explicitly allowed by the design's log
    record table — it must survive despite containing the substring 'Token'."""
    payload, _ = _log_one(claimToken="11111111-2222-3333-4444-555555555555")

    assert payload["claimToken"] == "11111111-2222-3333-4444-555555555555"


def test_redacts_absolute_paths_outside_data():
    payload, _ = _log_one(inputPath="/etc/secrets/worker-db-password")

    assert payload["inputPath"] == "[REDACTED]"


def test_preserves_absolute_paths_under_data():
    payload, _ = _log_one(inputPath="/data/assets/asset-1/original.stl")

    assert payload["inputPath"] == "/data/assets/asset-1/original.stl"


def test_never_logs_raw_stl_bytes():
    stl_bytes = b"\x00\x01\x02solidbinarystldata"
    payload, _ = _log_one(originalBytes=stl_bytes)

    assert payload["originalBytes"] != stl_bytes
    assert "solidbinarystldata" not in json.dumps(payload)
    assert payload["originalBytes"] == f"<binary:{len(stl_bytes)} bytes>"


def test_exception_logging_includes_structured_error_fields():
    stream = io.StringIO()
    configure_logging(stream=stream)
    logger = logging.getLogger("scenery_foundry_worker.test")

    try:
        raise ValueError("boom")
    except ValueError:
        bind(logger, jobId="job-1").exception("job_processing_failed")

    lines = [line for line in stream.getvalue().splitlines() if line.strip()]
    assert len(lines) == 1
    payload = json.loads(lines[0])
    assert payload["error.type"] == "ValueError"
    assert payload["error.message"] == "boom"
    assert "Traceback" in payload["error.stackTrace"]
    assert payload["jobId"] == "job-1"


def test_configure_logging_is_idempotent_and_does_not_duplicate_handlers():
    stream = io.StringIO()
    configure_logging(stream=stream)
    configure_logging(stream=stream)
    logger = logging.getLogger("scenery_foundry_worker.test")

    logger.info("single line")

    lines = [line for line in stream.getvalue().splitlines() if line.strip()]
    assert len(lines) == 1

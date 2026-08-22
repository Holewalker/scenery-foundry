"""Structured JSON logging (ADR-0008): stdlib `logging` + a JSON formatter, zero new dependencies.

Replaces the worker's previous `traceback.print_exc()`/`print()` stdout usage. One JSON object
per line, matching the backend's structured console logging so `jobId` correlates across both
services (design "Log record" table).

Never logged: password/password-hash, email, session id, CSRF token, `DUCKDNS_TOKEN`, any DB
password, absolute paths outside `/data`, raw STL bytes. Redaction is field-name based for
secrets (cheap and doesn't depend on guessing every possible secret shape) and value-based for
paths/bytes. `claimToken` is a deliberate exception: it is an ADR-0005 lease-fencing UUID, not a
secret, and the design's log record table explicitly lists it as an allowed contextual field —
the denylist below matches on substrings that never overlap with "claimtoken".
"""

from __future__ import annotations

import json
import logging
import re
import sys
from datetime import UTC, datetime
from typing import TextIO

_REDACTED = "[REDACTED]"

# Substrings checked against the *normalized* (lowercased, non-alnum stripped) key name.
# Deliberately does NOT include a bare "token": that would also catch `claimToken`, which the
# design allows.
_DENY_KEY_SUBSTRINGS = (
    "password",
    "passwd",
    "hash",
    "email",
    "sessionid",
    "csrf",
    "duckdns",
)

_ABS_PATH_TOKEN = re.compile(r"/[^\s\"']+")

_RESERVED_RECORD_ATTRS = frozenset(
    {
        "name",
        "msg",
        "args",
        "levelname",
        "levelno",
        "pathname",
        "filename",
        "module",
        "exc_info",
        "exc_text",
        "stack_info",
        "lineno",
        "funcName",
        "created",
        "msecs",
        "relativeCreated",
        "thread",
        "threadName",
        "processName",
        "process",
        "taskName",
        "message",
        "asctime",
    }
)


def _normalize_key(key: str) -> str:
    return re.sub(r"[^a-z0-9]", "", key.lower())


def _is_sensitive_key(key: str) -> bool:
    normalized = _normalize_key(key)
    return any(substr in normalized for substr in _DENY_KEY_SUBSTRINGS)


def _redact_absolute_paths(text: str) -> str:
    """Replaces whole path-shaped tokens outside `/data` with `[REDACTED]`. Matches whole
    whitespace-delimited tokens (not per-character) so `/data/assets/x/original.stl` is left
    intact rather than partially redacted."""

    def _replace(match: re.Match[str]) -> str:
        token = match.group(0)
        if token == "/data" or token.startswith("/data/"):
            return token
        return _REDACTED

    return _ABS_PATH_TOKEN.sub(_replace, text)


def _redact_value(key: str, value: object) -> object:
    if isinstance(value, (bytes, bytearray)):
        return f"<binary:{len(value)} bytes>"
    if _is_sensitive_key(key):
        return _REDACTED
    if isinstance(value, str):
        return _redact_absolute_paths(value)
    return value


class JsonFormatter(logging.Formatter):
    """One JSON object per line: `ts` (ISO-8601 UTC), `level`, `logger`, `message`,
    `service.name`, then any contextual fields attached via `bind()` or `extra=`."""

    def __init__(self, service_name: str) -> None:
        super().__init__()
        self._service_name = service_name

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, object] = {
            "ts": datetime.fromtimestamp(record.created, tz=UTC).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": _redact_absolute_paths(record.getMessage()),
            "service.name": self._service_name,
        }
        for key, value in record.__dict__.items():
            if key in _RESERVED_RECORD_ATTRS:
                continue
            payload[key] = _redact_value(key, value)
        if record.exc_info:
            error_type, error_value, _traceback = record.exc_info
            payload["error.type"] = error_type.__name__ if error_type else None
            payload["error.message"] = _redact_absolute_paths(str(error_value))
            payload["error.stackTrace"] = self.formatException(record.exc_info)
        return json.dumps(payload, default=str)


def configure_logging(
    service_name: str = "scenery-foundry.geometry-worker",
    stream: TextIO | None = None,
) -> None:
    """Installs the JSON formatter as the root logger's sole handler. Idempotent: re-running
    (e.g. once per test) replaces rather than duplicates handlers."""
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    for handler in list(root.handlers):
        root.removeHandler(handler)
    handler = logging.StreamHandler(stream if stream is not None else sys.stdout)
    handler.setFormatter(JsonFormatter(service_name))
    root.addHandler(handler)


def bind(logger: logging.Logger, **context: object) -> logging.LoggerAdapter:
    """Returns a `LoggerAdapter` that merges `context` (`jobId`, `jobType`, `subjectId`, ...)
    into every record, so call sites don't repeat correlation fields per log call."""
    return logging.LoggerAdapter(logger, extra=context, merge_extra=True)

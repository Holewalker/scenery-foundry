"""RFC 8785 canonicalization at the raw UTF-8 JSON boundary."""

import hashlib
import json
import math
from dataclasses import dataclass

import rfc8785

CONTRACT = "scenery-foundry.snapshot-jcs/v1"


class CanonicalizationError(ValueError):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class CanonicalResult:
    contract: str
    canonical_bytes: bytes
    sha256: str


def canonicalize(raw_utf8: bytes) -> CanonicalResult:
    try:
        decoded = raw_utf8.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise CanonicalizationError("INVALID_UTF8") from error

    try:
        value = json.loads(
            decoded,
            parse_int=_binary64_number,
            parse_float=_binary64_number,
            parse_constant=_reject_constant,
            object_pairs_hook=_unique_object,
        )
    except CanonicalizationError:
        raise
    except (json.JSONDecodeError, ValueError) as error:
        raise CanonicalizationError("INVALID_JSON") from error

    _validate_unicode(value)
    try:
        canonical_bytes = rfc8785.dumps(value)
    except (TypeError, ValueError) as error:
        raise CanonicalizationError("CANONICALIZATION_FAILED") from error
    return CanonicalResult(CONTRACT, canonical_bytes, hashlib.sha256(canonical_bytes).hexdigest())


def _binary64_number(lexeme: str) -> float:
    value = float(lexeme)
    if not math.isfinite(value):
        raise CanonicalizationError("NUMBER_OUT_OF_RANGE")
    return value


def _reject_constant(_: str) -> None:
    raise CanonicalizationError("INVALID_JSON")


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise CanonicalizationError("DUPLICATE_NAME")
        result[key] = value
    return result


def _validate_unicode(value: object) -> None:
    if isinstance(value, str):
        if any(0xD800 <= ord(character) <= 0xDFFF for character in value):
            raise CanonicalizationError("INVALID_UNICODE")
    elif isinstance(value, list):
        for item in value:
            _validate_unicode(item)
    elif isinstance(value, dict):
        for key, item in value.items():
            _validate_unicode(key)
            _validate_unicode(item)

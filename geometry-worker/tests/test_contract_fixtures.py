import json
from pathlib import Path

import pytest

from scenery_foundry_worker.jcs import CanonicalizationError, canonicalize

FIXTURE = Path(__file__).parents[2] / "contracts" / "fixtures" / "jcs-v1.json"


def fixture() -> dict:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def test_canonicalizes_every_shared_valid_case_through_the_production_adapter() -> None:
    data = fixture()
    assert data["contract"] == "scenery-foundry.snapshot-jcs/v1"
    for case in data["validCases"]:
        result = canonicalize(bytes.fromhex(case["rawUtf8Hex"]))
        assert result.canonical_bytes.hex() == case["canonicalUtf8Hex"]
        assert result.sha256 == case["sha256"]
        assert result.contract == "scenery-foundry.snapshot-jcs/v1"


def test_rejects_every_shared_invalid_case_before_producing_a_result() -> None:
    for case in fixture()["invalidCases"]:
        with pytest.raises(CanonicalizationError) as error:
            canonicalize(bytes.fromhex(case["rawUtf8Hex"]))
        assert error.value.code == case["error"]

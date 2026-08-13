import hashlib
import json
from pathlib import Path

FIXTURE = Path(__file__).parents[2] / "contracts" / "fixtures" / "jcs-v1.json"
JCS_V1_SHA256 = "9b5a71073c509b0d95adb6c65c7afe70ea2ae94ca9c9fce53adde0c224b83ea5"


def test_jcs_fixture_bytes_and_digest_are_stable() -> None:
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    canonical_bytes = fixture["canonical"].encode("utf-8")

    assert fixture["contract"] == "scenery-foundry.snapshot-jcs/v1"
    assert fixture["sha256"] == JCS_V1_SHA256
    assert hashlib.sha256(canonical_bytes).hexdigest() == JCS_V1_SHA256

import hashlib
import json
from pathlib import Path

FIXTURE = Path(__file__).parents[2] / "contracts" / "fixtures" / "jcs-v1.json"


def test_jcs_fixture_bytes_and_digest_are_stable() -> None:
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    canonical_bytes = fixture["canonical"].encode("utf-8")

    assert fixture["contract"] == "scenery-foundry.snapshot-jcs/v1"
    assert hashlib.sha256(canonical_bytes).hexdigest() == fixture["sha256"]

from scenery_foundry_worker.storage import publish_no_replace


def _write_temp(tmp_path, name: str, content: bytes):
    path = tmp_path / "tmp" / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return path


def test_first_publish_wins_and_removes_its_own_temp_source(tmp_path):
    source = _write_temp(tmp_path, "attempt-1.glb", b"winning glb bytes")
    destination = tmp_path / "assets" / "asset-1" / "preview.glb"

    publish_no_replace(source, destination)

    assert destination.read_bytes() == b"winning glb bytes"
    assert not source.exists()


def test_retry_after_a_transient_failure_publishes_exactly_one_winning_artifact(tmp_path):
    """A job retried after DATABASE_UNAVAILABLE: two attempts target the same final key."""
    destination = tmp_path / "assets" / "asset-1" / "preview.glb"
    first_attempt = _write_temp(tmp_path, "attempt-1.glb", b"first attempt bytes")
    second_attempt = _write_temp(tmp_path, "attempt-2.glb", b"second attempt bytes")

    publish_no_replace(first_attempt, destination)
    publish_no_replace(second_attempt, destination)

    assert destination.read_bytes() == b"first attempt bytes"
    assert not first_attempt.exists()
    assert not second_attempt.exists()  # cleaned up even though it lost the race

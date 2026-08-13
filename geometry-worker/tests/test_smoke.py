from scenery_foundry_worker.main import worker_identity


def test_worker_has_stable_identity() -> None:
    assert worker_identity() == "scenery-foundry.geometry-worker/v1"

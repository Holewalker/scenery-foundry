import json
from pathlib import Path

import numpy as np

FIXTURE = Path(__file__).parents[2] / "contracts" / "fixtures" / "transform-v1.json"


def test_column_major_transform_fixture_matches_row_batch_application() -> None:
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    assert fixture["contract"] == "scenery-foundry.transform"
    assert fixture["version"] == 1

    for case in fixture["cases"]:
        matrix = np.asarray(case["matrixColumnMajor"], dtype=np.float64).reshape((4, 4), order="F")
        point = np.asarray([*case["point"], 1.0], dtype=np.float64)
        expected = np.asarray(case["expectedPoint"], dtype=np.float64)

        column_result = (matrix @ point)[:3]
        row_batch_result = (point.reshape(1, 4) @ matrix.T)[0, :3]

        np.testing.assert_allclose(column_result, expected, rtol=0, atol=1e-12)
        np.testing.assert_array_equal(row_batch_result, column_result)

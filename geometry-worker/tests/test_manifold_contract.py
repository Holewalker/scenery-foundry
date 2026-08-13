from manifold3d import Error


def test_manifold_error_surface_matches_geometry_policy_v1() -> None:
    expected = {
        "NoError",
        "NonFiniteVertex",
        "NotManifold",
        "VertexOutOfBounds",
        "PropertiesWrongLength",
        "MissingPositionProperties",
        "MergeVectorsDifferentLengths",
        "MergeIndexOutOfBounds",
        "TransformWrongLength",
        "RunIndexWrongLength",
        "FaceIDWrongLength",
        "InvalidConstruction",
        "ResultTooLarge",
        "InvalidTangents",
        "Cancelled",
    }

    assert set(Error.__members__) == expected

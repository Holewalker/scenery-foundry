CREATE TABLE projects (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id),
    UNIQUE (id, owner_id)
);

CREATE TABLE prepared_assets (
    id uuid NOT NULL,
    project_id uuid NOT NULL REFERENCES projects(id),
    processing_status varchar(32) NOT NULL CHECK (processing_status IN ('READY', 'PENDING', 'FAILED')),
    geometry_status varchar(32) NOT NULL CHECK (geometry_status IN ('VALID_VOLUME', 'INVALID_VOLUME', 'PENDING')),
    storage_key text NOT NULL CHECK (storage_key <> ''),
    original_sha256 varchar(64) NOT NULL CHECK (original_sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (project_id, id),
    UNIQUE (id)
);

CREATE TABLE scene_objects (
    id bigint NOT NULL CHECK (id BETWEEN 1 AND 9007199254740991),
    project_id uuid NOT NULL REFERENCES projects(id),
    asset_id uuid NOT NULL,
    matrix_contract_version integer NOT NULL CHECK (matrix_contract_version = 1),
    translation_mm double precision[] NOT NULL CHECK (cardinality(translation_mm) = 3),
    quaternion_xyzw double precision[] NOT NULL CHECK (cardinality(quaternion_xyzw) = 4),
    scale double precision[] NOT NULL CHECK (cardinality(scale) = 3),
    matrix_world_column_major double precision[] NOT NULL CHECK (cardinality(matrix_world_column_major) = 16),
    PRIMARY KEY (project_id, id),
    FOREIGN KEY (project_id, asset_id) REFERENCES prepared_assets(project_id, id)
);

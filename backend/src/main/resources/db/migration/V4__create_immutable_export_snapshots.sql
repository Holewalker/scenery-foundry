CREATE TABLE combined_exports (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    captured_at timestamptz NOT NULL,
    snapshot_version integer NOT NULL CHECK (snapshot_version = 1),
    boolean_engine varchar(32) NOT NULL CHECK (boolean_engine = 'manifold3d'),
    boolean_engine_version varchar(32) NOT NULL CHECK (boolean_engine_version = '3.5.2'),
    geometry_policy_version varchar(64) NOT NULL CHECK (geometry_policy_version = 'scenery-foundry.geometry-policy/v1'),
    requested_output_format varchar(32) NOT NULL CHECK (requested_output_format = 'stl-binary'),
    UNIQUE (project_id, captured_at),
    FOREIGN KEY (project_id, owner_id) REFERENCES projects(id, owner_id)
);

CREATE TABLE export_snapshots (
    export_id uuid PRIMARY KEY REFERENCES combined_exports(id) ON DELETE RESTRICT,
    canonical_bytes bytea NOT NULL CHECK (octet_length(canonical_bytes) > 0),
    snapshot_sha256 varchar(64) NOT NULL CHECK (snapshot_sha256 ~ '^[0-9a-f]{64}$'),
    canonicalizer_contract varchar(64) NOT NULL CHECK (canonicalizer_contract = 'scenery-foundry.snapshot-jcs/v1')
);

CREATE FUNCTION reject_immutable_export_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'immutable export records cannot be modified';
END;
$$;

CREATE TRIGGER combined_exports_immutable BEFORE UPDATE OR DELETE ON combined_exports
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_export_mutation();
CREATE TRIGGER export_snapshots_immutable BEFORE UPDATE OR DELETE ON export_snapshots
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_export_mutation();

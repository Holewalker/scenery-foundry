CREATE TABLE platform_metadata (
    singleton_id smallint PRIMARY KEY CHECK (singleton_id = 1),
    schema_contract integer NOT NULL CHECK (schema_contract > 0)
);

INSERT INTO platform_metadata (singleton_id, schema_contract) VALUES (1, 1);

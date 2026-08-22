-- ADR-0007: scene saves stop being last-writer-wins. An additive, defaulted column (ADR-0002 expand-only):
-- no existing row or client is affected until the conditional UPDATE in JdbcOwnedSceneRepository starts
-- reading/writing it.
ALTER TABLE projects ADD COLUMN scene_version bigint NOT NULL DEFAULT 0;

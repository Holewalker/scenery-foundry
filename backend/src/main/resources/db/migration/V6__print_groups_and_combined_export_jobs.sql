-- Levels and Print Groups are structural twins (Phase 4 design: "print_groups: identical shape").
-- Levels ship with zero export semantics in this migration; Print Groups gain export semantics in later PRs.
CREATE TABLE levels (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    name text NOT NULL CHECK (name <> '' AND length(name) <= 120),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, project_id),
    FOREIGN KEY (project_id, owner_id) REFERENCES projects(id, owner_id)
);

CREATE TABLE print_groups (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    name text NOT NULL CHECK (name <> '' AND length(name) <= 120),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, project_id),
    FOREIGN KEY (project_id, owner_id) REFERENCES projects(id, owner_id)
);

-- Nullable: a SceneObject belongs to at most one Level and at most one Print Group (design D6).
-- The composite (x_id, project_id) FK is the Phase-3 isolation precedent (V5:52-53) applied verbatim:
-- the DB, not application code, forbids a scene object joining a level/group in another project.
ALTER TABLE scene_objects ADD COLUMN level_id uuid;
ALTER TABLE scene_objects ADD COLUMN print_group_id uuid;
ALTER TABLE scene_objects ADD CONSTRAINT scene_objects_level_project_fkey
    FOREIGN KEY (level_id, project_id) REFERENCES levels(id, project_id);
ALTER TABLE scene_objects ADD CONSTRAINT scene_objects_print_group_project_fkey
    FOREIGN KEY (print_group_id, project_id) REFERENCES print_groups(id, project_id);

-- Backs Combined Export's group-scoped scene object projection (PR5).
CREATE INDEX scene_objects_print_group_idx ON scene_objects (print_group_id, id)
    WHERE print_group_id IS NOT NULL;

-- Widen job_type to admit the second job type (proposal D1). No geometry_jobs column additions: the existing
-- output_storage_key/output_sha256/diagnostics/error_code/error_message columns are reused for both job types.
ALTER TABLE geometry_jobs DROP CONSTRAINT geometry_jobs_job_type_check;
ALTER TABLE geometry_jobs ADD CONSTRAINT geometry_jobs_job_type_check
    CHECK (job_type IN ('ASSET_PROCESSING','COMBINED_EXPORT'));

-- Re-key the claimable-job index by job_type now that two types share the queue (V5:83's index had no
-- job_type column, so JobRepository's `WHERE job_type = :jobType ...` claim query would filter it out after
-- the index scan rather than seek on it; a sparse job_type could scan past many rows of the other type before
-- finding a match). Lead with job_type to match the claim query's equality predicate.
DROP INDEX geometry_jobs_claimable_idx;
CREATE INDEX geometry_jobs_claimable_idx ON geometry_jobs (job_type, priority DESC, available_at, created_at, id)
    WHERE status IN ('PENDING', 'RETRY_WAIT');

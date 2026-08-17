-- Step 1: drop the old composite FK on scene_objects -> prepared_assets, found dynamically since its
-- auto-generated name collides with the existing single-column project_id -> projects(id) FK name.
DO $$
DECLARE fk_name text;
BEGIN
    SELECT conname INTO fk_name FROM pg_constraint
    WHERE conrelid = 'scene_objects'::regclass AND contype = 'f' AND confrelid = 'prepared_assets'::regclass;
    IF fk_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE scene_objects DROP CONSTRAINT %I', fk_name);
    END IF;
END $$;

-- Step 2: prepared_assets -> assets; owner-scoped instead of project-scoped.
ALTER TABLE prepared_assets RENAME TO assets;
ALTER TABLE assets DROP CONSTRAINT prepared_assets_pkey;
ALTER TABLE assets ADD COLUMN owner_id uuid;
UPDATE assets a SET owner_id = p.owner_id FROM projects p WHERE p.id = a.project_id;
ALTER TABLE assets ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE assets DROP COLUMN project_id;
ALTER TABLE assets ADD PRIMARY KEY (id);
ALTER TABLE assets ADD CONSTRAINT assets_id_owner_id_key UNIQUE (id, owner_id);
ALTER TABLE assets ADD CONSTRAINT assets_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES users(id);

-- Step 3-4: realign CHECKs (adds PROCESSING/UNKNOWN, drops legacy PENDING), data-fix legacy rows, new columns.
ALTER TABLE assets DROP CONSTRAINT prepared_assets_processing_status_check;
ALTER TABLE assets DROP CONSTRAINT prepared_assets_geometry_status_check;
UPDATE assets SET processing_status = 'UPLOADED' WHERE processing_status = 'PENDING';
UPDATE assets SET geometry_status = 'UNKNOWN' WHERE geometry_status = 'PENDING';
ALTER TABLE assets ADD CONSTRAINT assets_processing_status_check CHECK (processing_status IN ('UPLOADED','PROCESSING','READY','FAILED'));
ALTER TABLE assets ADD CONSTRAINT assets_geometry_status_check CHECK (geometry_status IN ('UNKNOWN','VALID_VOLUME','INVALID_VOLUME'));
ALTER TABLE assets ADD COLUMN preview_storage_key text;
ALTER TABLE assets ADD COLUMN preview_sha256 varchar(64);
ALTER TABLE assets ADD COLUMN triangle_count bigint;
ALTER TABLE assets ADD COLUMN bounds_min double precision[];
ALTER TABLE assets ADD COLUMN bounds_max double precision[];
ALTER TABLE assets ADD COLUMN volume_mm3 double precision;
ALTER TABLE assets ADD COLUMN geometry_policy_version int;
ALTER TABLE assets ADD COLUMN diagnostic_report jsonb;
ALTER TABLE assets ADD COLUMN error_code varchar(64);
ALTER TABLE assets ADD COLUMN created_at timestamptz NOT NULL DEFAULT clock_timestamp();

-- Step 5: scene_objects gains owner_id; shared column + composite FKs force project.owner == asset.owner.
ALTER TABLE scene_objects ADD COLUMN owner_id uuid;
UPDATE scene_objects o SET owner_id = p.owner_id FROM projects p WHERE p.id = o.project_id;
ALTER TABLE scene_objects ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE scene_objects ADD CONSTRAINT scene_objects_project_owner_fkey FOREIGN KEY (project_id, owner_id) REFERENCES projects(id, owner_id);
ALTER TABLE scene_objects ADD CONSTRAINT scene_objects_asset_owner_fkey FOREIGN KEY (asset_id, owner_id) REFERENCES assets(id, owner_id);

-- Step 6: geometry_jobs queue (ADR-0005 claim/lease/retry) + a minimal-grant worker role (ADR-0002).
CREATE TABLE geometry_jobs (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id),
    job_type varchar(32) NOT NULL CHECK (job_type IN ('ASSET_PROCESSING')),
    subject_id uuid NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','RUNNING','RETRY_WAIT','COMPLETED','FAILED')),
    priority int NOT NULL DEFAULT 0,
    attempt_count int NOT NULL DEFAULT 0,
    max_attempts int NOT NULL DEFAULT 3,
    available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    claim_token uuid,
    worker_id text,
    lease_expires_at timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    projected_at timestamptz,
    payload jsonb NOT NULL,
    output_storage_key text,
    output_sha256 varchar(64),
    diagnostics jsonb,
    error_code varchar(64),
    error_message text,
    idempotency_key text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (owner_id, job_type, idempotency_key)
);

CREATE INDEX geometry_jobs_claimable_idx ON geometry_jobs (priority DESC, available_at, created_at, id)
    WHERE status IN ('PENDING', 'RETRY_WAIT');
CREATE INDEX geometry_jobs_lease_idx ON geometry_jobs (lease_expires_at) WHERE status = 'RUNNING';

-- Roles are cluster-wide (not per-database); guard against re-running against a shared cluster.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'geometry_worker') THEN
        EXECUTE format('CREATE ROLE geometry_worker LOGIN PASSWORD %L', '${workerDbPassword}');
    END IF;
END $$;
GRANT SELECT, UPDATE ON geometry_jobs TO geometry_worker;

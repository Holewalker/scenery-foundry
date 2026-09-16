-- Additive, nullable project name; no backfill, no data loss.
ALTER TABLE projects ADD COLUMN name text;

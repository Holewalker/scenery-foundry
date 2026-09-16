-- Additive, nullable original filename; no backfill, no data loss.
ALTER TABLE assets ADD COLUMN original_filename text;

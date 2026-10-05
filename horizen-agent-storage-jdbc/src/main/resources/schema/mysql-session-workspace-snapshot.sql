-- Existing databases: add once before enabling Session-scoped workspace snapshots.
-- Existing shared USER snapshots cannot be assigned to a Session automatically.
ALTER TABLE ha_session ADD COLUMN snapshot_id VARCHAR(160) NULL;

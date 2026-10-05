-- Only for an existing unified history table that lacks the following columns/indexes.
-- Stop all Agent instances. Apply once, before mysql-artifact-history-upgrade.sql.
ALTER TABLE ha_conversation_history
    ADD COLUMN artifact_id VARCHAR(191) NULL,
    ADD COLUMN artifact_role VARCHAR(32) NULL,
    ADD INDEX idx_ha_history_artifact (owner_key, artifact_id, created_at),
    ADD INDEX idx_ha_history_artifact_session (owner_key, session_id, record_type, artifact_role, created_at);

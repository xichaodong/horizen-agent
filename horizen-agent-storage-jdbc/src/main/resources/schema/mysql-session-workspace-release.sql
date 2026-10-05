-- Existing databases: stop all Agent instances and apply once before starting this version.
-- Existing Sessions remain unbound until their next NORMAL execution. Pending legacy calls
-- without a full publication binding must not resume using a guessed/current version.
ALTER TABLE ha_session
    ADD COLUMN project_id BIGINT NULL,
    ADD COLUMN agent_key VARCHAR(128) NULL,
    ADD COLUMN workspace_release_id BIGINT NULL,
    ADD COLUMN workspace_release_hash CHAR(64) NULL;
-- ha_skill_session_binding only identified the skill bundle, not the complete publication.
-- Do not copy its hash into workspace_release_hash. Keep it for audit until old data is archived.

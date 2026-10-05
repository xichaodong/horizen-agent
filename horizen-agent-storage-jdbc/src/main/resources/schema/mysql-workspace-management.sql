-- Apply in the Agent runtime database, never the Horizen Server database.
-- Existing ha_workspace_file uses owner_key + agent_key + scope_key + path_hash as its primary key.
ALTER TABLE ha_workspace_file
    ADD COLUMN media_type VARCHAR(255) NOT NULL DEFAULT 'text/plain; charset=utf-8' AFTER content_ref;

CREATE TABLE IF NOT EXISTS ha_workspace (
    project_id BIGINT NOT NULL,
    agent_key VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    current_release_id BIGINT,
    updated_by VARCHAR(191) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(project_id,agent_key)
);
CREATE TABLE IF NOT EXISTS ha_workspace_release (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    agent_key VARCHAR(128) NOT NULL,
    release_no BIGINT NOT NULL,
    release_hash CHAR(64) NOT NULL,
    manifest_json LONGTEXT NOT NULL,
    notes VARCHAR(1000) NOT NULL DEFAULT '',
    created_by VARCHAR(191) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(id),
    UNIQUE KEY uk_ha_workspace_release(project_id,agent_key,release_no)
);

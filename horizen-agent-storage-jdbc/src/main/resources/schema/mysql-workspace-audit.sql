-- Apply once to the Agent database. Historic rows have no complete snapshots;
-- do not infer or reconstruct full before/after text from old change fragments.
ALTER TABLE ha_workspace_operation
    ADD COLUMN audit_sequence BIGINT NOT NULL AUTO_INCREMENT,
    ADD UNIQUE KEY uk_ha_workspace_audit_sequence(audit_sequence),
    ADD COLUMN actor_id VARCHAR(191),
    ADD COLUMN before_version BIGINT,
    ADD COLUMN after_version BIGINT,
    ADD COLUMN before_ref VARCHAR(1024),
    ADD COLUMN after_ref VARCHAR(1024),
    ADD COLUMN before_checksum CHAR(64),
    ADD COLUMN after_checksum CHAR(64),
    ADD COLUMN before_size BIGINT,
    ADD COLUMN after_size BIGINT,
    ADD COLUMN media_type VARCHAR(255) NOT NULL DEFAULT 'text/plain; charset=utf-8',
    ADD KEY idx_ha_workspace_audit_file(owner_key,agent_key,scope_key,path_hash,audit_sequence),
    ADD KEY idx_ha_workspace_audit_scope(owner_key,agent_key,scope_key,audit_sequence);

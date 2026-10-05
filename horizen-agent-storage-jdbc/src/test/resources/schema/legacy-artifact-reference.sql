CREATE TABLE ha_artifact_reference (
    owner_key VARCHAR(191) NOT NULL,
    reference_id VARCHAR(191) NOT NULL,
    artifact_id VARCHAR(191) NOT NULL,
    session_id VARCHAR(191) NOT NULL,
    turn_id VARCHAR(191) NOT NULL,
    role VARCHAR(32) NOT NULL,
    tool_call_id VARCHAR(191),
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (owner_key, reference_id),
    KEY idx_ha_artifact_reference_artifact (owner_key, artifact_id),
    KEY idx_ha_artifact_reference_session (owner_key, session_id, created_at),
    KEY idx_ha_artifact_reference_turn (owner_key, turn_id)
);

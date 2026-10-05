-- Idempotent migration for generic cloud-Agent artifacts and ask_user requests.
-- Apply after the pre-existing runtime tables in schema/mysql.sql.

CREATE TABLE IF NOT EXISTS ha_artifact (
    owner_key VARCHAR(191) NOT NULL,
    artifact_id VARCHAR(191) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    media_type VARCHAR(255) NOT NULL,
    content_ref VARCHAR(1024),
    size_bytes BIGINT,
    checksum_sha256 CHAR(64),
    parent_artifact_id VARCHAR(191),
    source VARCHAR(32) NOT NULL,
    source_ref VARCHAR(512) NOT NULL,
    expires_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    deleted_at TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_key, artifact_id),
    UNIQUE KEY uk_ha_artifact_source (owner_key, source, source_ref),
    KEY idx_ha_artifact_expiry (status, expires_at)
);

-- Artifact references now use ha_conversation_history; see mysql-artifact-history-upgrade.sql.
-- ask_user now uses ha_interaction. For an existing installation see
-- mysql-interaction.sql and mysql-interaction-upgrade.sql; do not recreate ha_ask_user.

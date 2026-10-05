-- Retired tables, used only to validate upgrades.
CREATE TABLE ha_approval (
    owner_key VARCHAR(191) NOT NULL,
    approval_id VARCHAR(191) NOT NULL,
    session_id VARCHAR(191) NOT NULL,
    turn_id VARCHAR(191) NOT NULL,
    request_reply_id VARCHAR(191),
    tool_call_id VARCHAR(191) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    tool_content LONGTEXT NOT NULL,
    tool_arguments_json LONGTEXT NOT NULL,
    presentation_json LONGTEXT,
    status VARCHAR(32) NOT NULL,
    requested_by VARCHAR(191) NOT NULL,
    expires_at TIMESTAMP(6),
    decided_by VARCHAR(191),
    decided_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_key, approval_id),
    UNIQUE KEY uk_ha_approval_tool (owner_key, turn_id, tool_call_id),
    KEY idx_ha_approval_pending (owner_key, session_id, status, created_at)
);

CREATE TABLE ha_ask_user (
    owner_key VARCHAR(191) NOT NULL,
    ask_user_id VARCHAR(191) NOT NULL,
    session_id VARCHAR(191) NOT NULL,
    turn_id VARCHAR(191) NOT NULL,
    reply_id VARCHAR(191),
    tool_call_id VARCHAR(191) NOT NULL,
    questions_json LONGTEXT NOT NULL,
    answers_json LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    resolved_at TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_key, ask_user_id),
    UNIQUE KEY uk_ha_ask_user_tool (owner_key, turn_id, tool_call_id),
    KEY idx_ha_ask_user_pending (owner_key, session_id, status, created_at)
);

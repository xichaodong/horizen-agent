-- The three retired tables, kept only as an upgrade test fixture.
CREATE TABLE ha_message (
 owner_key VARCHAR(191) NOT NULL, message_id VARCHAR(191) NOT NULL,
 session_id VARCHAR(191) NOT NULL, turn_id VARCHAR(191) NOT NULL,
 role VARCHAR(32) NOT NULL, status VARCHAR(32) NOT NULL, content LONGTEXT NOT NULL,
 message_sequence BIGINT NOT NULL, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY (owner_key,message_id), UNIQUE KEY (owner_key,session_id,message_sequence)
);
CREATE TABLE ha_turn_timeline_event (
 event_sequence BIGINT NOT NULL AUTO_INCREMENT, owner_key VARCHAR(191) NOT NULL,
 session_id VARCHAR(191) NOT NULL, turn_id VARCHAR(191) NOT NULL,
 payload_json LONGTEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL, PRIMARY KEY (event_sequence)
);
CREATE TABLE ha_presentation_block (
 owner_key VARCHAR(191) NOT NULL, block_id VARCHAR(191) NOT NULL,
 session_id VARCHAR(191) NOT NULL, turn_id VARCHAR(191) NOT NULL,
 tool_call_id VARCHAR(191) NOT NULL, tool_name VARCHAR(255) NOT NULL,
 block_type VARCHAR(128) NOT NULL, schema_version INT NOT NULL, block_position INT NOT NULL,
 payload_json LONGTEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL, PRIMARY KEY (owner_key,block_id)
);

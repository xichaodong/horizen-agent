-- Run once against an existing workspace schema while all Horizen Agent instances are stopped.
-- New databases must use mysql.sql; this script is only for the rename and metadata columns.
RENAME TABLE ha_memory_operation TO ha_workspace_operation;

ALTER TABLE ha_workspace_file
    ADD COLUMN workspace_area VARCHAR(32) NOT NULL DEFAULT 'USER' AFTER scope_key,
    ADD COLUMN file_kind VARCHAR(32) NOT NULL DEFAULT 'DOCUMENT' AFTER workspace_area,
    ADD COLUMN write_policy VARCHAR(32) NOT NULL DEFAULT 'AGENT' AFTER file_kind;

ALTER TABLE ha_workspace_operation
    ADD COLUMN actor_type VARCHAR(32) NOT NULL DEFAULT 'AGENT' AFTER operation_type;

UPDATE ha_workspace_file
SET file_kind = CASE
        WHEN file_path = 'AGENTS.md' THEN 'AGENTS'
        WHEN file_path = 'MEMORY.md' THEN 'MEMORY'
        WHEN file_path = 'PLAN.md' OR file_path LIKE 'plans/%' THEN 'PLAN'
        WHEN file_path = 'tools.json' THEN 'TOOLS'
        WHEN file_path LIKE 'knowledge/%' THEN 'KNOWLEDGE'
        WHEN file_path LIKE 'skills/%' THEN 'SKILL'
        WHEN file_path LIKE 'memory/%' THEN 'MEMORY_LOG'
        ELSE 'DOCUMENT'
    END,
    write_policy = CASE
        WHEN file_path IN ('AGENTS.md', 'tools.json')
            OR file_path LIKE 'knowledge/%' OR file_path LIKE 'skills/%' THEN 'SERVER'
        ELSE 'AGENT'
    END,
    workspace_area = CASE
        WHEN file_path LIKE 'sessions/%' THEN 'SESSION'
        WHEN file_path LIKE 'tasks/%' THEN 'TASK'
        ELSE 'USER'
    END;

-- Existing databases: run once before enabling the server-backed session catalog.
ALTER TABLE ha_session
    ADD COLUMN title VARCHAR(255) NOT NULL DEFAULT '新对话' AFTER created_by,
    ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE AFTER title,
    ADD COLUMN last_message_at TIMESTAMP(6) NULL AFTER pinned;

UPDATE ha_session
SET last_message_at = updated_at
WHERE last_message_at IS NULL;

UPDATE ha_session AS session
JOIN (
    SELECT owner_key, session_id, MIN(message_sequence) AS first_sequence
    FROM ha_message
    WHERE role = 'USER'
    GROUP BY owner_key, session_id
) AS first_message
    ON first_message.owner_key = session.owner_key
    AND first_message.session_id = session.session_id
JOIN ha_message AS message
    ON message.owner_key = first_message.owner_key
    AND message.session_id = first_message.session_id
    AND message.message_sequence = first_message.first_sequence
SET session.title = LEFT(TRIM(SUBSTRING_INDEX(message.content, '\n', 1)), 20)
WHERE session.title = '新对话'
  AND TRIM(SUBSTRING_INDEX(message.content, '\n', 1)) <> '';

ALTER TABLE ha_session
    MODIFY COLUMN last_message_at TIMESTAMP(6) NOT NULL,
    ADD KEY idx_ha_session_catalog (
        owner_key, status, pinned, last_message_at, session_id
    );

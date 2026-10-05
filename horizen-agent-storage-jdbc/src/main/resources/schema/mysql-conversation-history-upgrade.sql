-- Existing databases only. Stop ALL old/new Agent instances before running.
-- 1. Back up the three legacy tables.
-- 2. Run mysql-conversation-history.sql to create an EMPTY destination table.
-- 3. Run this script once. No legacy table is dropped; rollback keeps its source data.
-- Timeline cursor values are preserved. New history sequences start above the old maximum.
START TRANSACTION;

INSERT INTO ha_conversation_history (
    history_sequence, owner_key, session_id, turn_id, record_type, record_id,
    payload_json, created_at, updated_at
)
SELECT event_sequence, owner_key, session_id, turn_id, 'EVENT',
       CONCAT('legacy-event-', event_sequence), payload_json, created_at, created_at
FROM ha_turn_timeline_event ORDER BY event_sequence;

INSERT INTO ha_conversation_history (
    owner_key, session_id, turn_id, record_type, record_id, message_sequence,
    payload_json, created_at, updated_at
)
SELECT owner_key, session_id, turn_id, 'MESSAGE', message_id, message_sequence,
       JSON_OBJECT('role', role, 'status', status, 'content', content), created_at, updated_at
FROM ha_message ORDER BY created_at, message_sequence;

INSERT INTO ha_conversation_history (
    owner_key, session_id, turn_id, record_type, record_id, payload_json, created_at, updated_at
)
SELECT owner_key, session_id, turn_id, 'PRESENTATION', block_id,
       JSON_OBJECT('toolCallId', tool_call_id, 'toolName', tool_name,
           'block', JSON_OBJECT('blockId', block_id, 'type', block_type,
               'schemaVersion', schema_version, 'position', block_position,
               'data', JSON_EXTRACT(payload_json, '$'))), created_at, created_at
FROM ha_presentation_block ORDER BY created_at;

CREATE TEMPORARY TABLE ha_history_merge (
    canonical_sequence BIGINT NOT NULL PRIMARY KEY,
    event_sequence BIGINT NOT NULL UNIQUE
);

INSERT INTO ha_history_merge (canonical_sequence, event_sequence)
SELECT h.history_sequence, MIN(e.history_sequence)
FROM ha_conversation_history h JOIN ha_conversation_history e
  ON h.owner_key=e.owner_key AND h.session_id=e.session_id AND h.turn_id=e.turn_id
WHERE h.record_type='MESSAGE' AND e.record_type='EVENT'
  AND JSON_UNQUOTE(JSON_EXTRACT(h.payload_json, '$.role'))='ASSISTANT'
  AND JSON_UNQUOTE(JSON_EXTRACT(h.payload_json, '$.status'))='FINAL'
  AND JSON_UNQUOTE(JSON_EXTRACT(e.payload_json, '$.type'))='done'
  AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(e.payload_json, '$.source')), '') IN ('', 'null')
  AND RIGHT(JSON_UNQUOTE(JSON_EXTRACT(e.payload_json, '$.text')),
      CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(h.payload_json, '$.content'))))
      = JSON_UNQUOTE(JSON_EXTRACT(h.payload_json, '$.content'))
GROUP BY h.history_sequence;

INSERT INTO ha_history_merge (canonical_sequence, event_sequence)
SELECT h.history_sequence, MIN(e.history_sequence)
FROM ha_conversation_history h JOIN ha_conversation_history e
  ON h.owner_key=e.owner_key AND h.session_id=e.session_id AND h.turn_id=e.turn_id
WHERE h.record_type='PRESENTATION' AND e.record_type='EVENT'
  AND JSON_UNQUOTE(JSON_EXTRACT(e.payload_json, '$.type'))='presentation_created'
  AND JSON_UNQUOTE(JSON_EXTRACT(
      IF(JSON_VALID(JSON_UNQUOTE(JSON_EXTRACT(e.payload_json, '$.details'))),
          JSON_UNQUOTE(JSON_EXTRACT(e.payload_json, '$.details')), '{}'), '$.block.blockId'))=h.record_id
  AND JSON_UNQUOTE(JSON_EXTRACT(
      IF(JSON_VALID(JSON_UNQUOTE(JSON_EXTRACT(e.payload_json, '$.details'))),
          JSON_UNQUOTE(JSON_EXTRACT(e.payload_json, '$.details')), '{}'), '$.toolCallId'))
      =JSON_UNQUOTE(JSON_EXTRACT(h.payload_json, '$.toolCallId'))
GROUP BY h.history_sequence;

UPDATE ha_conversation_history h
JOIN ha_history_merge m ON h.history_sequence=m.canonical_sequence
JOIN ha_conversation_history e ON e.history_sequence=m.event_sequence
SET h.timeline_sequence=e.history_sequence,
    h.timeline_created_at=e.created_at,
    h.timeline_payload_json=CASE WHEN h.record_type='MESSAGE' THEN
        JSON_SET(JSON_REMOVE(e.payload_json, '$.text'), '$._history_text_prefix',
            LEFT(JSON_UNQUOTE(JSON_EXTRACT(e.payload_json, '$.text')),
                CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(e.payload_json, '$.text')))
                - CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(h.payload_json, '$.content')))))
        ELSE JSON_REMOVE(e.payload_json, '$.details') END;

DELETE e FROM ha_conversation_history e
JOIN ha_history_merge m ON e.history_sequence=m.event_sequence;
DROP TEMPORARY TABLE ha_history_merge;
COMMIT;

-- These counts must agree before starting the new version.
SELECT (SELECT COUNT(*) FROM ha_message) AS old_messages,
       (SELECT COUNT(*) FROM ha_conversation_history WHERE record_type='MESSAGE') AS new_messages,
       (SELECT COUNT(*) FROM ha_presentation_block) AS old_cards,
       (SELECT COUNT(*) FROM ha_conversation_history WHERE record_type='PRESENTATION') AS new_cards,
       (SELECT COUNT(*) FROM ha_turn_timeline_event) AS old_events,
       (SELECT COUNT(*) FROM ha_conversation_history
        WHERE record_type='EVENT' OR timeline_sequence IS NOT NULL) AS new_events;
-- After acceptance, archive the legacy tables through the normal database release process.
-- Never restart the old version after new writes without a reverse data migration.

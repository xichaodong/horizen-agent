-- Existing databases only. Back up and stop all Agent instances before applying once.
-- The unified history table must already have the Artifact columns/indexes.
-- Leaves ha_artifact_reference intact for verification/archive; new code never reads it.
START TRANSACTION;
INSERT INTO ha_conversation_history (
    owner_key, session_id, turn_id, record_type, record_id, artifact_id, artifact_role,
    payload_json, created_at, updated_at
)
SELECT owner_key, session_id, turn_id, 'ARTIFACT_REFERENCE', reference_id, artifact_id, role,
       JSON_OBJECT('artifactId', artifact_id, 'role', role, 'toolCallId', tool_call_id), created_at, created_at
FROM ha_artifact_reference;

-- IDs only: no file contents, signed URLs or private tokens are copied into messages.
SET @ha_history_previous_group_concat_max_len = @@SESSION.group_concat_max_len;
SET SESSION group_concat_max_len = 1048576;
UPDATE ha_conversation_history m JOIN (
    SELECT owner_key, session_id, turn_id,
           JSON_EXTRACT(CONCAT('[', GROUP_CONCAT(DISTINCT JSON_QUOTE(artifact_id)
               ORDER BY artifact_id SEPARATOR ','), ']'), '$') AS ids
    FROM ha_artifact_reference WHERE role = 'INPUT'
    GROUP BY owner_key, session_id, turn_id
) i ON m.owner_key=i.owner_key AND m.session_id=i.session_id AND m.turn_id=i.turn_id
SET m.payload_json=JSON_SET(m.payload_json, '$.artifactIds',
    JSON_MERGE(COALESCE(JSON_EXTRACT(m.payload_json, '$.artifactIds'), JSON_ARRAY()), i.ids))
WHERE m.record_type='MESSAGE' AND JSON_UNQUOTE(JSON_EXTRACT(m.payload_json, '$.role'))='USER';
SET SESSION group_concat_max_len = @ha_history_previous_group_concat_max_len;
COMMIT;

SELECT (SELECT COUNT(*) FROM ha_artifact_reference) AS old_references,
       (SELECT COUNT(*) FROM ha_conversation_history WHERE record_type='ARTIFACT_REFERENCE') AS new_references;
-- Verify history attachments/recent outputs before archiving the old table via the database release process.

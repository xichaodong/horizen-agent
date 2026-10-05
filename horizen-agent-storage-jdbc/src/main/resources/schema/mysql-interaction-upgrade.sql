-- Existing databases only: back up ha_approval/ha_ask_user and stop ALL Agent instances.
-- Create an EMPTY ha_interaction with mysql-interaction.sql, then run this script once.
-- Keeps IDs, reply IDs, versions, frozen requests, responses and lifecycle timestamps.
-- No legacy table is dropped. New/old application versions cannot run together.
START TRANSACTION;
INSERT INTO ha_interaction (
    owner_key, interaction_type, interaction_id, session_id, turn_id, reply_id,
    tool_call_id, status, request_json, response_json, expires_at, resolved_at,
    created_at, updated_at, version
)
SELECT owner_key, 'APPROVAL', approval_id, session_id, turn_id, request_reply_id,
       tool_call_id, status,
       JSON_OBJECT('toolName', tool_name, 'toolContent', tool_content,
           'toolArgumentsJson', tool_arguments_json, 'requestedBy', requested_by,
           'presentationJson', presentation_json),
       JSON_OBJECT('decidedBy', decided_by, 'approved',
           JSON_EXTRACT(CASE WHEN status='APPROVED' THEN 'true'
               WHEN status='DENIED' THEN 'false' ELSE 'null' END, '$')),
       expires_at, decided_at, created_at, updated_at, version
FROM ha_approval;

INSERT INTO ha_interaction (
    owner_key, interaction_type, interaction_id, session_id, turn_id, reply_id,
    tool_call_id, status, request_json, response_json, expires_at, resolved_at,
    created_at, updated_at, version
)
SELECT owner_key, 'ASK_USER', ask_user_id, session_id, turn_id, reply_id,
       tool_call_id, status, JSON_OBJECT('questionsJson', questions_json),
       JSON_OBJECT('answersJson', answers_json), expires_at, resolved_at,
       created_at, COALESCE(resolved_at, created_at), version
FROM ha_ask_user;
COMMIT;

SELECT (SELECT COUNT(*) FROM ha_approval) AS old_approvals,
       (SELECT COUNT(*) FROM ha_interaction WHERE interaction_type='APPROVAL') AS new_approvals,
       (SELECT COUNT(*) FROM ha_ask_user) AS old_questions,
       (SELECT COUNT(*) FROM ha_interaction WHERE interaction_type='ASK_USER') AS new_questions;
-- Verify pending requests and completed responses through the new application before archiving sources.
-- New writes require reverse synchronization before rolling back to an old application version.

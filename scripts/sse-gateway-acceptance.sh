#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
BASE_URL=${AGENT_GATEWAY_BASE_URL:-http://127.0.0.1:8790}
REQUEST_TIMEOUT=${AGENT_GATEWAY_REQUEST_TIMEOUT:-30}
HEARTBEAT_WAIT_SECONDS=${AGENT_GATEWAY_HEARTBEAT_WAIT_SECONDS:-20}
RUN_ID=${AGENT_GATEWAY_RUN_ID:-"$(date +%Y%m%d-%H%M%S)-$$"}
OUTPUT_DIR=${AGENT_GATEWAY_OUTPUT_DIR:-"$ROOT_DIR/target/gateway-acceptance/$RUN_ID"}
REPORT_FILE=${AGENT_GATEWAY_REPORT:-"$OUTPUT_DIR/report.md"}

for command_name in curl jq python3; do
  command -v "$command_name" >/dev/null 2>&1 \
    || { echo "[gateway] missing command: $command_name" >&2; exit 2; }
done
mkdir -p "$OUTPUT_DIR"

event_objects() {
  awk '/^data:/{sub(/^data:/, ""); print}' "$1"
}

event_count() {
  event_objects "$1" | jq -s --arg type "$2" 'map(select(.type == $type)) | length'
}

turn_id_from_stream() {
  event_objects "$1" | jq -s -r 'map(select(.type == "turn_start"))[0].id // empty'
}

upstream_from_headers() {
  awk 'BEGIN{IGNORECASE=1} /^x-horizen-upstream:/{gsub("\r", ""); print $2; exit}' "$1"
}

post_json() {
  local path=$1
  local payload=$2
  local output=$3
  curl --fail --silent --show-error --max-time "$REQUEST_TIMEOUT" \
    -H 'Content-Type: application/json' -H 'Accept: application/json' \
    --data "$payload" "$BASE_URL$path" > "$output"
}

subscribe_turn() {
  local session_id=$1
  local turn_id=$2
  local headers=$3
  local output=$4
  local payload
  payload=$(jq -cn --arg session "$session_id" --arg turn "$turn_id" \
    '{sessionId:$session,expectedTurnId:$turn,afterTimelineSequence:0,afterEventSequence:0}')
  curl --fail --silent --show-error --max-time "$REQUEST_TIMEOUT" \
    -D "$headers" -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
    --data "$payload" "$BASE_URL/api/session/subscribe" > "$output"
}

status=$(curl --fail --silent --show-error --max-time 5 "$BASE_URL/api/status")
jq -e '.ready == true and .modelName == "scripted-web"' <<< "$status" >/dev/null \
  || { echo "[gateway] target must run ready SCRIPTED mode" >&2; exit 3; }

session_id="gateway-recovery-$RUN_ID"
request_id="gateway-recovery-request-$RUN_ID"
payload=$(jq -cn --arg session "$session_id" --arg request "$request_id" \
  '{sessionId:$session,requestId:$request,message:"stream gateway recovery",artifactIds:[]}')
curl --silent --max-time 1 -D "$OUTPUT_DIR/recovery-start.headers" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  --data "$payload" -w '%{time_starttransfer}' "$BASE_URL/api/chat/stream" \
  -o "$OUTPUT_DIR/recovery-start.sse" > "$OUTPUT_DIR/first-byte-seconds.txt" || true
turn_id=$(turn_id_from_stream "$OUTPUT_DIR/recovery-start.sse")
[[ -n "$turn_id" ]] || { echo "[gateway] no turn_start received" >&2; exit 4; }
subscribe_turn "$session_id" "$turn_id" "$OUTPUT_DIR/recovery-finish.headers" \
  "$OUTPUT_DIR/recovery-finish.sse"
start_upstream=$(upstream_from_headers "$OUTPUT_DIR/recovery-start.headers")
finish_upstream=$(upstream_from_headers "$OUTPUT_DIR/recovery-finish.headers")
cross_instance=unknown
if [[ -n "$start_upstream" && -n "$finish_upstream" ]]; then
  cross_instance=no
  if [[ "$start_upstream" != "$finish_upstream" ]]; then cross_instance=yes; fi
fi
complete_recovery=no
if [[ "$(event_count "$OUTPUT_DIR/recovery-finish.sse" turn_start)" == "1" \
    && "$(event_count "$OUTPUT_DIR/recovery-finish.sse" text_delta)" == "3" \
    && "$(event_count "$OUTPUT_DIR/recovery-finish.sse" done)" == "1" \
    && "$(event_count "$OUTPUT_DIR/recovery-finish.sse" error)" == "0" ]]; then
  complete_recovery=yes
fi
first_byte_seconds=$(tr -d '\r\n' < "$OUTPUT_DIR/first-byte-seconds.txt")
first_byte_fast=$(python3 - "$first_byte_seconds" <<'PY'
import sys
print("yes" if float(sys.argv[1] or "99") < 1.0 else "no")
PY
)

approval_session="gateway-approval-$RUN_ID"
approval_payload=$(jq -cn --arg session "$approval_session" \
  '{sessionId:$session,requestId:($session+"-request"),message:"approval gateway acceptance",artifactIds:[]}')
curl --fail --silent --show-error --max-time "$REQUEST_TIMEOUT" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  --data "$approval_payload" "$BASE_URL/api/chat/stream" > "$OUTPUT_DIR/approval-paused.sse"
approval_turn=$(turn_id_from_stream "$OUTPUT_DIR/approval-paused.sse")
approval_query=$(jq -cn --arg session "$approval_session" --arg turn "$approval_turn" \
  '{sessionId:$session,turnId:$turn}')
post_json /api/session/approvals/query "$approval_query" "$OUTPUT_DIR/approval-pending.json"
approval_id=$(jq -r '.approvals[0].approvalId // empty' "$OUTPUT_DIR/approval-pending.json")
approval_decision=$(jq -cn --arg session "$approval_session" --arg turn "$approval_turn" \
  --arg approval "$approval_id" \
  '{sessionId:$session,turnId:$turn,decisions:[{approvalId:$approval,approved:true}]}')
post_json /api/session/approval/decide "$approval_decision" "$OUTPUT_DIR/approval-decision.json"
subscribe_turn "$approval_session" "$approval_turn" "$OUTPUT_DIR/approval-finish.headers" \
  "$OUTPUT_DIR/approval-finish.sse"
approval_ok=no
if [[ "$(event_count "$OUTPUT_DIR/approval-paused.sse" approval_required)" == "1" \
    && "$(event_count "$OUTPUT_DIR/approval-finish.sse" done)" == "1" \
    && "$(event_count "$OUTPUT_DIR/approval-finish.sse" error)" == "0" ]]; then
  approval_ok=yes
fi

ask_session="gateway-ask-$RUN_ID"
ask_payload=$(jq -cn --arg session "$ask_session" \
  '{sessionId:$session,requestId:($session+"-request"),message:"ask gateway acceptance",artifactIds:[]}')
curl --fail --silent --show-error --max-time "$REQUEST_TIMEOUT" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  --data "$ask_payload" "$BASE_URL/api/chat/stream" > "$OUTPUT_DIR/ask-paused.sse"
ask_turn=$(turn_id_from_stream "$OUTPUT_DIR/ask-paused.sse")
ask_user_id=$(event_objects "$OUTPUT_DIR/ask-paused.sse" | jq -s -r \
  'map(select(.type == "ask_user_required"))[0].details | fromjson | .askUserId // empty')
ask_answer=$(jq -cn --arg ask "$ask_user_id" \
  '{askUserId:$ask,answers:[{questionId:"scope",selectedOptionIds:["week"],customText:""}],skip:false}')
post_json /api/ask-user/answer "$ask_answer" "$OUTPUT_DIR/ask-answer.json"
subscribe_turn "$ask_session" "$ask_turn" "$OUTPUT_DIR/ask-finish.headers" \
  "$OUTPUT_DIR/ask-finish.sse"
ask_ok=no
if [[ "$(event_count "$OUTPUT_DIR/ask-paused.sse" ask_user_required)" == "1" \
    && "$(event_count "$OUTPUT_DIR/ask-finish.sse" done)" == "1" \
    && "$(event_count "$OUTPUT_DIR/ask-finish.sse" error)" == "0" ]]; then
  ask_ok=yes
fi

heartbeat_session="gateway-heartbeat-$RUN_ID"
heartbeat_payload=$(jq -cn --arg session "$heartbeat_session" \
  '{sessionId:$session,requestId:($session+"-request"),message:"wait gateway heartbeat",artifactIds:[]}')
curl --silent --max-time "$HEARTBEAT_WAIT_SECONDS" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  --data "$heartbeat_payload" "$BASE_URL/api/chat/stream" \
  > "$OUTPUT_DIR/heartbeat.sse" || true
heartbeat_turn=$(turn_id_from_stream "$OUTPUT_DIR/heartbeat.sse")
heartbeat_seen=no
if grep -q '^:keep-alive' "$OUTPUT_DIR/heartbeat.sse"; then heartbeat_seen=yes; fi
cancel_payload=$(jq -cn --arg session "$heartbeat_session" --arg turn "$heartbeat_turn" \
  '{sessionId:$session,expectedTurnId:$turn}')
post_json /api/session/cancel "$cancel_payload" "$OUTPUT_DIR/heartbeat-cancel.json"

slow_session="gateway-slow-$RUN_ID"
slow_payload=$(jq -cn --arg session "$slow_session" \
  '{sessionId:$session,requestId:($session+"-request"),message:"stream slow gateway",artifactIds:[]}')
curl --silent --limit-rate 32 --max-time 12 \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  --data "$slow_payload" "$BASE_URL/api/chat/stream" > "$OUTPUT_DIR/slow.sse" &
slow_pid=$!
fast_session="gateway-fast-$RUN_ID"
fast_payload=$(jq -cn --arg session "$fast_session" \
  '{sessionId:$session,requestId:($session+"-request"),message:"stream gateway fast",artifactIds:[]}')
fast_started=$(python3 -c 'import time; print(time.time())')
curl --fail --silent --show-error --max-time 10 \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  --data "$fast_payload" "$BASE_URL/api/chat/stream" > "$OUTPUT_DIR/fast.sse"
fast_finished=$(python3 -c 'import time; print(time.time())')
wait "$slow_pid" || true
fast_elapsed=$(python3 - "$fast_started" "$fast_finished" <<'PY'
import sys
print(f"{float(sys.argv[2]) - float(sys.argv[1]):.3f}")
PY
)
slow_isolated=no
if [[ "$(event_count "$OUTPUT_DIR/fast.sse" done)" == "1" ]]; then
  slow_isolated=$(python3 - "$fast_elapsed" <<'PY'
import sys
print("yes" if float(sys.argv[1]) < 8.0 else "no")
PY
  )
fi

resources_recovered=no
for attempt in $(seq 1 30); do
  status_a=$(curl --fail --silent --show-error --max-time 5 "$BASE_URL/api/stream/status")
  status_b=$(curl --fail --silent --show-error --max-time 5 "$BASE_URL/api/stream/status")
  if jq -en --argjson a "$status_a" --argjson b "$status_b" '
    [$a,$b] | all(.activeConnections == 0 and .queuedEvents == 0 and .queuedBytes == 0
      and .redisPolling.activePollers == 0 and .leaseRenewal.trackedTurns == 0
      and .databasePool.threadsAwaitingConnection == 0
      and .redisPools.commands.threadsAwaitingConnection == 0)' >/dev/null; then
    resources_recovered=yes
    break
  fi
  sleep 1
done

cat > "$REPORT_FILE" <<EOF
# SSE 网关验收

- 运行标识：\`$RUN_ID\`
- 入口：\`$BASE_URL\`
- 起始实例：\`${start_upstream:-unknown}\`
- 恢复实例：\`${finish_upstream:-unknown}\`

| 检查项 | 结果 | 证据 |
| --- | :---: | --- |
| 首包未被缓冲 | $first_byte_fast | ${first_byte_seconds}s |
| 跨实例路由 | $cross_instance | ${start_upstream:-unknown} → ${finish_upstream:-unknown} |
| 断线恢复完整 | $complete_recovery | 1 turn_start、3 text_delta、1 done、0 error |
| 审批跨实例恢复 | $approval_ok | approval_required 后批准并完成 |
| ask_user 跨实例恢复 | $ask_ok | ask_user_required 后回答并完成 |
| 空闲心跳 | $heartbeat_seen | 等待 ${HEARTBEAT_WAIT_SECONDS}s |
| 慢客户端隔离 | $slow_isolated | 并行快速请求 ${fast_elapsed}s 完成 |
| 资源回落 | $resources_recovered | 两次状态采样均为 0 |

原始响应位于 \`$OUTPUT_DIR\`。
EOF

echo "[gateway] report: $REPORT_FILE"
if [[ "$first_byte_fast" != "yes" || "$complete_recovery" != "yes" \
    || "$approval_ok" != "yes" || "$ask_ok" != "yes" \
    || "$heartbeat_seen" != "yes" || "$slow_isolated" != "yes" \
    || "$resources_recovered" != "yes" \
    || "$cross_instance" == "no" ]]; then
  echo "[gateway] acceptance failed" >&2
  exit 5
fi
echo "[gateway] acceptance passed"

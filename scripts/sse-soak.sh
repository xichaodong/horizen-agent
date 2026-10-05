#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
ENDPOINTS_TEXT=${AGENT_SOAK_ENDPOINTS:-"http://127.0.0.1:8787 http://127.0.0.1:8788"}
DURATION_SECONDS=${AGENT_SOAK_DURATION_SECONDS:-1800}
BATCH_SIZE=${AGENT_SOAK_BATCH_SIZE:-4}
BATCH_PAUSE_SECONDS=${AGENT_SOAK_BATCH_PAUSE_SECONDS:-5}
SAMPLE_INTERVAL_SECONDS=${AGENT_SOAK_SAMPLE_INTERVAL_SECONDS:-5}
SPECIAL_INTERVAL_SECONDS=${AGENT_SOAK_SPECIAL_INTERVAL_SECONDS:-300}
START_DISCONNECT_AFTER=${AGENT_SOAK_START_DISCONNECT_AFTER:-1}
REQUEST_TIMEOUT=${AGENT_SOAK_REQUEST_TIMEOUT:-30}
LONG_CANCEL_AFTER_SECONDS=${AGENT_SOAK_LONG_CANCEL_AFTER_SECONDS:-65}
TTL_WAIT_SECONDS=${AGENT_SOAK_TTL_WAIT_SECONDS:-90}
REDIS_HOST=${AGENT_SOAK_REDIS_HOST:-127.0.0.1}
REDIS_PORT=${AGENT_SOAK_REDIS_PORT:-6379}
REDIS_PREFIX=${AGENT_SOAK_REDIS_PREFIX:-}
RUN_ID=${AGENT_SOAK_RUN_ID:-"$(date +%Y%m%d-%H%M%S)-$$"}
OUTPUT_DIR=${AGENT_SOAK_OUTPUT_DIR:-"$ROOT_DIR/target/soak/$RUN_ID"}
REPORT_FILE=${AGENT_SOAK_REPORT:-"$OUTPUT_DIR/report.md"}

read -r -a ENDPOINTS <<< "$ENDPOINTS_TEXT"

for command_name in curl jq redis-cli python3; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "[soak] missing command: $command_name" >&2
    exit 2
  fi
done
if [[ ${#ENDPOINTS[@]} -lt 2 || -z "$REDIS_PREFIX" ]]; then
  echo "[soak] two endpoints and AGENT_SOAK_REDIS_PREFIX are required" >&2
  exit 2
fi
if ((DURATION_SECONDS < LONG_CANCEL_AFTER_SECONDS + 10)); then
  echo "[soak] duration must exceed the long-task cancellation delay" >&2
  exit 2
fi

mkdir -p "$OUTPUT_DIR"
SAMPLES_FILE="$OUTPUT_DIR/status.ndjson"
STOP_SAMPLE_FILE="$OUTPUT_DIR/stop-sampling"
: > "$SAMPLES_FILE"

seconds_now() {
  python3 -c 'import time; print(time.time())'
}

fetch_statuses() {
  local statuses='[]'
  local endpoint
  local current
  for endpoint in "${ENDPOINTS[@]}"; do
    current=$(curl --fail --silent --show-error --max-time 3 "$endpoint/api/stream/status")
    statuses=$(jq -cn --argjson values "$statuses" --argjson value "$current" \
      '$values + [$value]')
  done
  printf '%s\n' "$statuses"
}

aggregate_statuses() {
  jq -c '
    def total($value_path): [.[] | getpath($value_path) // 0] | add;
    {
      activeConnections: total(["activeConnections"]),
      queuedEvents: total(["queuedEvents"]),
      queuedBytes: total(["queuedBytes"]),
      writerActiveThreads: total(["writerActiveThreads"]),
      writerQueueSize: total(["writerQueueSize"]),
      activePollers: total(["redisPolling", "activePollers"]),
      redisReadFailures: total(["redisPolling", "readFailureCount"]),
      trackedTurns: total(["leaseRenewal", "trackedTurns"]),
      renewedTurns: total(["leaseRenewal", "renewedTurns"]),
      renewalFailures: total(["leaseRenewal", "renewalFailures"]),
      activeLeaseBatches: total(["leaseRenewal", "activeBatches"]),
      databaseActiveConnections: total(["databasePool", "activeConnections"]),
      databaseAwaitingConnections: total(["databasePool", "threadsAwaitingConnection"]),
      redisCommandActive: total(["redisPools", "commands", "activeConnections"]),
      redisCommandIdle: total(["redisPools", "commands", "idleConnections"]),
      redisCommandAwaiting: total(["redisPools", "commands", "threadsAwaitingConnection"]),
      redisCommandMaximum: total(["redisPools", "commands", "maximumPoolSize"]),
      redisSubscriptionActive: total(["redisPools", "subscriptions", "activeConnections"]),
      redisSubscriptionIdle: total(["redisPools", "subscriptions", "idleConnections"]),
      redisSubscriptionAwaiting: total(["redisPools", "subscriptions", "threadsAwaitingConnection"]),
      redisSubscriptionMaximum: total(["redisPools", "subscriptions", "maximumPoolSize"]),
      heapUsedBytes: total(["process", "heapUsedBytes"]),
      liveThreads: total(["process", "liveThreads"])
    }'
}

redis_value() {
  local section=$1
  local key=$2
  redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" --raw INFO "$section" \
    | tr -d '\r' | awk -F: -v wanted="$key" '$1 == wanted {print $2; exit}'
}

event_key_count() {
  local count
  count=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" --scan \
    --pattern "${REDIS_PREFIX}bus:log*" | wc -l | tr -d ' ')
  printf '%s\n' "$count"
}

sample_session_state_ttl() {
  local key
  key=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" --scan \
    --pattern "${REDIS_PREFIX}session:*" | awk 'NR == 1 {first = $0} END {print first}')
  if [[ -z "$key" ]]; then
    echo -2
  else
    redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" TTL "$key"
  fi
}

sample_loop() {
  local statuses
  local aggregate
  while [[ ! -f "$STOP_SAMPLE_FILE" ]]; do
    if statuses=$(fetch_statuses 2>/dev/null); then
      aggregate=$(printf '%s\n' "$statuses" | aggregate_statuses)
      jq -cn --argjson status "$aggregate" --argjson timestamp "$(seconds_now)" \
        --argjson eventKeys "$(event_key_count)" \
        --argjson redisMemory "$(redis_value memory used_memory)" \
        '$status + {timestamp:$timestamp,eventKeys:$eventKeys,redisMemory:$redisMemory}' \
        >> "$SAMPLES_FILE"
    fi
    sleep "$SAMPLE_INTERVAL_SECONDS"
  done
}

event_objects() {
  awk '/^data:/{sub(/^data:/, ""); print}' "$1"
}

turn_id_from_stream() {
  event_objects "$1" | jq -s -r 'map(select(.type == "turn_start"))[0].id // empty'
}

event_count() {
  local file=$1
  local type=$2
  event_objects "$file" | jq -s --arg type "$type" 'map(select(.type == $type)) | length'
}

post_json() {
  local endpoint=$1
  local path=$2
  local payload=$3
  local output=$4
  curl --fail --silent --show-error --max-time "$REQUEST_TIMEOUT" \
    -H 'Content-Type: application/json' -H 'Accept: application/json' \
    --data "$payload" "$endpoint$path" > "$output"
}

start_disconnected() {
  local endpoint=$1
  local session_id=$2
  local message=$3
  local request_id=$4
  local output=$5
  local payload
  payload=$(jq -cn --arg session "$session_id" --arg request "$request_id" \
    --arg message "$message" \
    '{sessionId:$session,requestId:$request,message:$message,artifactIds:[]}')
  curl --silent --max-time "$START_DISCONNECT_AFTER" \
    -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
    --data "$payload" "$endpoint/api/chat/stream" > "$output" || true
}

subscribe_turn() {
  local endpoint=$1
  local session_id=$2
  local turn_id=$3
  local output=$4
  local payload
  payload=$(jq -cn --arg session "$session_id" --arg turn "$turn_id" \
    '{sessionId:$session,expectedTurnId:$turn,afterTimelineSequence:0,afterEventSequence:0}')
  curl --fail --silent --show-error --max-time "$REQUEST_TIMEOUT" \
    -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
    --data "$payload" "$endpoint/api/session/subscribe" > "$output"
}

run_cross_batch() {
  local batch_number=$1
  local batch_dir="$OUTPUT_DIR/batch-$batch_number"
  local start_pids=()
  local subscribe_pids=()
  local sessions=()
  local destinations=()
  local request_number
  local origin_index
  local destination_index
  local turn_id
  mkdir -p "$batch_dir"

  for ((request_number = 0; request_number < BATCH_SIZE; request_number++)); do
    origin_index=$((request_number % ${#ENDPOINTS[@]}))
    destination_index=$(((origin_index + 1) % ${#ENDPOINTS[@]}))
    sessions+=("soak-$RUN_ID-$batch_number-$request_number")
    destinations+=("${ENDPOINTS[$destination_index]}")
    start_disconnected "${ENDPOINTS[$origin_index]}" \
      "${sessions[$request_number]}" "stream soak $RUN_ID-$batch_number-$request_number" \
      "request-$RUN_ID-$batch_number-$request_number" \
      "$batch_dir/start-$request_number.sse" &
    start_pids+=("$!")
  done
  for request_pid in "${start_pids[@]}"; do wait "$request_pid"; done

  for ((request_number = 0; request_number < BATCH_SIZE; request_number++)); do
    turn_id=$(turn_id_from_stream "$batch_dir/start-$request_number.sse")
    if [[ -z "$turn_id" ]]; then
      return 1
    fi
    subscribe_turn "${destinations[$request_number]}" "${sessions[$request_number]}" \
      "$turn_id" "$batch_dir/finish-$request_number.sse" &
    subscribe_pids+=("$!")
  done
  for request_pid in "${subscribe_pids[@]}"; do wait "$request_pid"; done

  for ((request_number = 0; request_number < BATCH_SIZE; request_number++)); do
    if [[ "$(event_count "$batch_dir/finish-$request_number.sse" turn_start)" != "1" \
        || "$(event_count "$batch_dir/finish-$request_number.sse" text_delta)" != "3" \
        || "$(event_count "$batch_dir/finish-$request_number.sse" done)" != "1" \
        || "$(event_count "$batch_dir/finish-$request_number.sse" error)" != "0" ]]; then
      return 1
    fi
  done
  LAST_COMPLETED_SESSION=${sessions[$((BATCH_SIZE - 1))]}
  return 0
}

run_approval() {
  local sequence=$1
  local session_id="soak-approval-$RUN_ID-$sequence"
  local paused="$OUTPUT_DIR/approval-$sequence-paused.sse"
  local pending="$OUTPUT_DIR/approval-$sequence-pending.json"
  local decision="$OUTPUT_DIR/approval-$sequence-decision.json"
  local completed="$OUTPUT_DIR/approval-$sequence-completed.sse"
  local turn_id
  local approval_id
  local payload
  start_disconnected "${ENDPOINTS[0]}" "$session_id" "approval soak $sequence" \
    "approval-request-$RUN_ID-$sequence" "$paused"
  turn_id=$(turn_id_from_stream "$paused")
  [[ -n "$turn_id" && "$(event_count "$paused" approval_required)" == "1" ]] || return 1
  payload=$(jq -cn --arg session "$session_id" --arg turn "$turn_id" \
    '{sessionId:$session,turnId:$turn}')
  post_json "${ENDPOINTS[1]}" /api/session/approvals/query "$payload" "$pending"
  approval_id=$(jq -r '.approvals[0].approvalId // empty' "$pending")
  [[ -n "$approval_id" ]] || return 1
  payload=$(jq -cn --arg session "$session_id" --arg turn "$turn_id" \
    --arg approval "$approval_id" \
    '{sessionId:$session,turnId:$turn,decisions:[{approvalId:$approval,approved:true}]}')
  post_json "${ENDPOINTS[1]}" /api/session/approval/decide "$payload" "$decision"
  subscribe_turn "${ENDPOINTS[0]}" "$session_id" "$turn_id" "$completed"
  [[ "$(event_count "$completed" done)" == "1" \
      && "$(event_count "$completed" error)" == "0" ]]
}

run_ask_user() {
  local sequence=$1
  local session_id="soak-ask-$RUN_ID-$sequence"
  local paused="$OUTPUT_DIR/ask-$sequence-paused.sse"
  local answer="$OUTPUT_DIR/ask-$sequence-answer.json"
  local completed="$OUTPUT_DIR/ask-$sequence-completed.sse"
  local turn_id
  local ask_user_id
  local payload
  start_disconnected "${ENDPOINTS[0]}" "$session_id" "ask soak $sequence" \
    "ask-request-$RUN_ID-$sequence" "$paused"
  turn_id=$(turn_id_from_stream "$paused")
  ask_user_id=$(event_objects "$paused" | jq -s -r \
    'map(select(.type == "ask_user_required"))[0].details | fromjson | .askUserId // empty')
  [[ -n "$turn_id" && -n "$ask_user_id" ]] || return 1
  payload=$(jq -cn --arg ask "$ask_user_id" \
    '{askUserId:$ask,answers:[{questionId:"scope",selectedOptionIds:["week"],customText:""}],skip:false}')
  post_json "${ENDPOINTS[1]}" /api/ask-user/answer "$payload" "$answer"
  subscribe_turn "${ENDPOINTS[0]}" "$session_id" "$turn_id" "$completed"
  [[ "$(event_count "$completed" done)" == "1" \
      && "$(event_count "$completed" error)" == "0" ]]
}

run_long_cancel() {
  local sequence=$1
  local origin_index=$((sequence % ${#ENDPOINTS[@]}))
  local destination_index=$(((origin_index + 1) % ${#ENDPOINTS[@]}))
  local session_id="soak-wait-$RUN_ID-$sequence"
  local started="$OUTPUT_DIR/wait-$sequence-started.sse"
  local completed="$OUTPUT_DIR/wait-$sequence-completed.sse"
  local cancel_result="$OUTPUT_DIR/wait-$sequence-cancel.json"
  local result="$OUTPUT_DIR/wait-$sequence-result.txt"
  local turn_id
  local payload
  start_disconnected "${ENDPOINTS[$origin_index]}" "$session_id" "wait soak $sequence" \
    "wait-request-$RUN_ID-$sequence" "$started"
  turn_id=$(turn_id_from_stream "$started")
  if [[ -z "$turn_id" ]]; then
    echo failed > "$result"
    return
  fi
  subscribe_turn "${ENDPOINTS[$destination_index]}" "$session_id" "$turn_id" "$completed" &
  local subscriber_pid=$!
  sleep "$LONG_CANCEL_AFTER_SECONDS"
  payload=$(jq -cn --arg session "$session_id" --arg turn "$turn_id" \
    '{sessionId:$session,expectedTurnId:$turn}')
  if ! post_json "${ENDPOINTS[$destination_index]}" /api/session/cancel \
      "$payload" "$cancel_result"; then
    echo failed > "$result"
    wait "$subscriber_pid" || true
    return
  fi
  if wait "$subscriber_pid" \
      && [[ "$(event_count "$completed" cancelled)" == "1" \
      && "$(event_count "$completed" error)" == "0" ]]; then
    echo passed > "$result"
  else
    echo failed > "$result"
  fi
}

wait_for_runtime_recovery() {
  local deadline=$(( $(date +%s) + 30 ))
  local aggregate
  local outstanding
  while (( $(date +%s) < deadline )); do
    aggregate=$(fetch_statuses | aggregate_statuses)
    outstanding=$(jq \
      '.activeConnections + .queuedEvents + .queuedBytes + .activePollers + .trackedTurns + .activeLeaseBatches' \
      <<< "$aggregate")
    if [[ "$outstanding" == "0" ]]; then return 0; fi
    sleep 1
  done
  return 1
}

wait_for_event_ttl() {
  local deadline=$(( $(date +%s) + TTL_WAIT_SECONDS ))
  while (( $(date +%s) < deadline )); do
    if [[ "$(event_key_count)" == "0" ]]; then return 0; fi
    sleep 2
  done
  return 1
}

redis_version=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" --raw INFO server \
  | tr -d '\r' | awk -F: '$1 == "redis_version" {print $2; exit}')
[[ -n "$redis_version" ]] || { echo "[soak] Redis is unavailable" >&2; exit 3; }
for endpoint in "${ENDPOINTS[@]}"; do
  app_status=$(curl --fail --silent --show-error --max-time 3 "$endpoint/api/status")
  jq -e '.ready == true and .modelName == "scripted-web"' <<< "$app_status" >/dev/null \
    || { echo "[soak] endpoint must run ready SCRIPTED mode: $endpoint" >&2; exit 3; }
done

baseline_status=$(fetch_statuses | aggregate_statuses)
baseline_event_keys=$(event_key_count)
baseline_redis_memory=$(redis_value memory used_memory)
baseline_redis_clients=$(redis_value clients connected_clients)
baseline_evicted_keys=$(redis_value stats evicted_keys)
start_epoch=$(date +%s)
end_epoch=$((start_epoch + DURATION_SECONDS))

sample_loop &
sampler_pid=$!
run_long_cancel 0 &
long_pid_a=$!
run_long_cancel 1 &
long_pid_b=$!

normal_attempted=0
normal_passed=0
approval_attempted=0
approval_passed=0
ask_attempted=0
ask_passed=0
batch_number=0
special_sequence=0
next_special_epoch=$start_epoch
LAST_COMPLETED_SESSION=""

while (( $(date +%s) < end_epoch )); do
  batch_number=$((batch_number + 1))
  normal_attempted=$((normal_attempted + BATCH_SIZE))
  if run_cross_batch "$batch_number"; then
    normal_passed=$((normal_passed + BATCH_SIZE))
  fi
  current_epoch=$(date +%s)
  if ((current_epoch >= next_special_epoch)); then
    special_sequence=$((special_sequence + 1))
    approval_attempted=$((approval_attempted + 1))
    if run_approval "$special_sequence"; then approval_passed=$((approval_passed + 1)); fi
    ask_attempted=$((ask_attempted + 1))
    if run_ask_user "$special_sequence"; then ask_passed=$((ask_passed + 1)); fi
    next_special_epoch=$((current_epoch + SPECIAL_INTERVAL_SECONDS))
  fi
  if ((batch_number % 10 == 0)); then
    echo "[soak] elapsed=$((current_epoch - start_epoch))s normal=$normal_passed/$normal_attempted approval=$approval_passed/$approval_attempted ask=$ask_passed/$ask_attempted"
  fi
  sleep "$BATCH_PAUSE_SECONDS"
done

wait "$long_pid_a" || true
wait "$long_pid_b" || true
long_passed=0
for result_file in "$OUTPUT_DIR"/wait-*-result.txt; do
  if [[ "$(tr -d '\r\n' < "$result_file")" == "passed" ]]; then
    long_passed=$((long_passed + 1))
  fi
done

runtime_recovered=no
if wait_for_runtime_recovery; then runtime_recovered=yes; fi
after_traffic_status=$(fetch_statuses | aggregate_statuses)
after_traffic_event_keys=$(event_key_count)
session_state_ttl=$(sample_session_state_ttl)
session_state_ttl_applied=no
if ((session_state_ttl > 0)); then session_state_ttl_applied=yes; fi

ttl_cleaned=no
if wait_for_event_ttl; then ttl_cleaned=yes; fi
after_ttl_event_keys=$(event_key_count)

history_recovered=no
if [[ -n "$LAST_COMPLETED_SESSION" ]]; then
  history_payload=$(jq -cn --arg session "$LAST_COMPLETED_SESSION" '{sessionId:$session}')
  if post_json "${ENDPOINTS[1]}" /api/session/messages/query "$history_payload" \
      "$OUTPUT_DIR/history-after-ttl.json" \
      && jq -e '.messages | any(.role == "assistant" and .content == "第一段，第二段，第三段。")' \
      "$OUTPUT_DIR/history-after-ttl.json" >/dev/null; then
    history_recovered=yes
  fi
fi

: > "$STOP_SAMPLE_FILE"
wait "$sampler_pid" || true
final_status=$(fetch_statuses | aggregate_statuses)
final_redis_memory=$(redis_value memory used_memory)
final_redis_clients=$(redis_value clients connected_clients)
final_evicted_keys=$(redis_value stats evicted_keys)
actual_duration=$(( $(date +%s) - start_epoch ))

metrics=$(jq -s '
  def maximum($field): map(.[$field]) | max;
  {
    samples:length,
    peakConnections:maximum("activeConnections"),
    peakPollers:maximum("activePollers"),
    peakTrackedTurns:maximum("trackedTurns"),
    peakQueuedEvents:maximum("queuedEvents"),
    peakQueuedBytes:maximum("queuedBytes"),
    peakWriterThreads:maximum("writerActiveThreads"),
    peakWriterQueue:maximum("writerQueueSize"),
    peakDatabaseActive:maximum("databaseActiveConnections"),
    peakDatabaseAwaiting:maximum("databaseAwaitingConnections"),
    peakRedisCommandActive:maximum("redisCommandActive"),
    peakRedisCommandIdle:maximum("redisCommandIdle"),
    peakRedisCommandAwaiting:maximum("redisCommandAwaiting"),
    peakRedisSubscriptionActive:maximum("redisSubscriptionActive"),
    peakRedisSubscriptionIdle:maximum("redisSubscriptionIdle"),
    peakRedisSubscriptionAwaiting:maximum("redisSubscriptionAwaiting"),
    peakHeap:maximum("heapUsedBytes"),
    peakThreads:maximum("liveThreads"),
    peakEventKeys:maximum("eventKeys"),
    peakRedisMemory:maximum("redisMemory"),
    redisReadFailures:maximum("redisReadFailures"),
    renewalFailures:maximum("renewalFailures")
  }' "$SAMPLES_FILE")

cat > "$REPORT_FILE" <<EOF
# SSE 双实例持续运行验收

- 运行标识：\`$RUN_ID\`
- Redis：\`$redis_version\`（\`$REDIS_HOST:$REDIS_PORT\`）
- Redis 前缀：\`$REDIS_PREFIX\`
- 实例：\`$ENDPOINTS_TEXT\`
- 计划持续时间：${DURATION_SECONDS}s；包含 TTL 等待的实际时间：${actual_duration}s
- 流量：每批 $BATCH_SIZE 个跨实例断线恢复，批间隔 ${BATCH_PAUSE_SECONDS}s
- 特殊流程：每 ${SPECIAL_INTERVAL_SECONDS}s 执行审批和 ask_user；另有 2 个长任务在 ${LONG_CANCEL_AFTER_SECONDS}s 后跨实例取消

## 结果

| 场景 | 通过/执行 | 结果 |
| --- | ---: | :---: |
| 普通跨实例恢复 | $normal_passed/$normal_attempted | $([[ "$normal_passed" == "$normal_attempted" ]] && echo passed || echo failed) |
| 审批后恢复 | $approval_passed/$approval_attempted | $([[ "$approval_passed" == "$approval_attempted" ]] && echo passed || echo failed) |
| ask_user 后恢复 | $ask_passed/$ask_attempted | $([[ "$ask_passed" == "$ask_attempted" ]] && echo passed || echo failed) |
| 长任务续租后取消 | $long_passed/2 | $([[ "$long_passed" == "2" ]] && echo passed || echo failed) |
| 运行资源回落 | $runtime_recovered | $runtime_recovered |
| Redis 事件键 TTL 清理 | $after_ttl_event_keys 个残留 | $ttl_cleaned |
| Redis Session 状态 TTL | ${session_state_ttl}s | $session_state_ttl_applied |
| TTL 后 MySQL 历史恢复 | $history_recovered | $history_recovered |

## 峰值资源

| 指标（两实例合计） | 峰值 |
| --- | ---: |
| SSE 连接 | $(jq -r .peakConnections <<< "$metrics") |
| Redis 轮询器 | $(jq -r .peakPollers <<< "$metrics") |
| 续租 Turn | $(jq -r .peakTrackedTurns <<< "$metrics") |
| SSE 队列事件/字节 | $(jq -r .peakQueuedEvents <<< "$metrics")/$(jq -r .peakQueuedBytes <<< "$metrics") |
| SSE writer 活跃/排队 | $(jq -r .peakWriterThreads <<< "$metrics")/$(jq -r .peakWriterQueue <<< "$metrics") |
| JDBC 活跃/等待 | $(jq -r .peakDatabaseActive <<< "$metrics")/$(jq -r .peakDatabaseAwaiting <<< "$metrics") |
| Redis 命令池活跃/空闲/等待 | $(jq -r .peakRedisCommandActive <<< "$metrics")/$(jq -r .peakRedisCommandIdle <<< "$metrics")/$(jq -r .peakRedisCommandAwaiting <<< "$metrics") |
| Redis 订阅池活跃/空闲/等待 | $(jq -r .peakRedisSubscriptionActive <<< "$metrics")/$(jq -r .peakRedisSubscriptionIdle <<< "$metrics")/$(jq -r .peakRedisSubscriptionAwaiting <<< "$metrics") |
| JVM 堆 | $(jq -r .peakHeap <<< "$metrics") |
| JVM 线程 | $(jq -r .peakThreads <<< "$metrics") |
| Redis 事件键 | $(jq -r .peakEventKeys <<< "$metrics") |
| Redis used_memory | $(jq -r .peakRedisMemory <<< "$metrics") |
| Redis 读取失败 | $(jq -r .redisReadFailures <<< "$metrics") |
| 租约续租失败 | $(jq -r .renewalFailures <<< "$metrics") |

## 前后与清理状态

| 指标 | 开始前 | 流量停止后 | TTL 等待后 |
| --- | ---: | ---: | ---: |
| activeConnections | $(jq -r .activeConnections <<< "$baseline_status") | $(jq -r .activeConnections <<< "$after_traffic_status") | $(jq -r .activeConnections <<< "$final_status") |
| queuedEvents/queuedBytes | $(jq -r .queuedEvents <<< "$baseline_status")/$(jq -r .queuedBytes <<< "$baseline_status") | $(jq -r .queuedEvents <<< "$after_traffic_status")/$(jq -r .queuedBytes <<< "$after_traffic_status") | $(jq -r .queuedEvents <<< "$final_status")/$(jq -r .queuedBytes <<< "$final_status") |
| activePollers | $(jq -r .activePollers <<< "$baseline_status") | $(jq -r .activePollers <<< "$after_traffic_status") | $(jq -r .activePollers <<< "$final_status") |
| trackedTurns | $(jq -r .trackedTurns <<< "$baseline_status") | $(jq -r .trackedTurns <<< "$after_traffic_status") | $(jq -r .trackedTurns <<< "$final_status") |
| JDBC active/awaiting | $(jq -r .databaseActiveConnections <<< "$baseline_status")/$(jq -r .databaseAwaitingConnections <<< "$baseline_status") | $(jq -r .databaseActiveConnections <<< "$after_traffic_status")/$(jq -r .databaseAwaitingConnections <<< "$after_traffic_status") | $(jq -r .databaseActiveConnections <<< "$final_status")/$(jq -r .databaseAwaitingConnections <<< "$final_status") |
| Redis command active/idle/maximum | $(jq -r .redisCommandActive <<< "$baseline_status")/$(jq -r .redisCommandIdle <<< "$baseline_status")/$(jq -r .redisCommandMaximum <<< "$baseline_status") | $(jq -r .redisCommandActive <<< "$after_traffic_status")/$(jq -r .redisCommandIdle <<< "$after_traffic_status")/$(jq -r .redisCommandMaximum <<< "$after_traffic_status") | $(jq -r .redisCommandActive <<< "$final_status")/$(jq -r .redisCommandIdle <<< "$final_status")/$(jq -r .redisCommandMaximum <<< "$final_status") |
| Redis subscription active/idle/maximum | $(jq -r .redisSubscriptionActive <<< "$baseline_status")/$(jq -r .redisSubscriptionIdle <<< "$baseline_status")/$(jq -r .redisSubscriptionMaximum <<< "$baseline_status") | $(jq -r .redisSubscriptionActive <<< "$after_traffic_status")/$(jq -r .redisSubscriptionIdle <<< "$after_traffic_status")/$(jq -r .redisSubscriptionMaximum <<< "$after_traffic_status") | $(jq -r .redisSubscriptionActive <<< "$final_status")/$(jq -r .redisSubscriptionIdle <<< "$final_status")/$(jq -r .redisSubscriptionMaximum <<< "$final_status") |
| heapUsedBytes | $(jq -r .heapUsedBytes <<< "$baseline_status") | $(jq -r .heapUsedBytes <<< "$after_traffic_status") | $(jq -r .heapUsedBytes <<< "$final_status") |
| liveThreads | $(jq -r .liveThreads <<< "$baseline_status") | $(jq -r .liveThreads <<< "$after_traffic_status") | $(jq -r .liveThreads <<< "$final_status") |
| Redis 事件键 | $baseline_event_keys | $after_traffic_event_keys | $after_ttl_event_keys |
| Redis used_memory | $baseline_redis_memory | - | $final_redis_memory |
| Redis connected_clients | $baseline_redis_clients | - | $final_redis_clients |
| Redis evicted_keys | $baseline_evicted_keys | - | $final_evicted_keys |

Redis 客户端连接按命令池与订阅池的显式上限判定；池内空闲连接可以保留复用，不要求回到冷启动数量。
事件键必须在流量期间被观测到，并在 TTL 等待后清零。原始 SSE、JSON 响应和 $(jq -r .samples <<< "$metrics") 组资源采样
位于 \`$OUTPUT_DIR\`。
EOF

overall_passed=true
if [[ "$normal_passed" != "$normal_attempted" \
    || "$approval_passed" != "$approval_attempted" \
    || "$ask_passed" != "$ask_attempted" \
    || "$long_passed" != "2" \
    || "$runtime_recovered" != "yes" \
    || "$ttl_cleaned" != "yes" \
    || "$session_state_ttl_applied" != "yes" \
    || "$history_recovered" != "yes" \
    || "$(jq -r .redisReadFailures <<< "$metrics")" != "0" \
    || "$(jq -r .renewalFailures <<< "$metrics")" != "0" \
    || "$(jq -r .peakDatabaseAwaiting <<< "$metrics")" != "0" \
    || "$(jq -r .peakRedisCommandAwaiting <<< "$metrics")" != "0" \
    || "$(jq -r .peakRedisSubscriptionAwaiting <<< "$metrics")" != "0" \
    || "$(jq -r .peakEventKeys <<< "$metrics")" == "0" \
    || "$after_traffic_event_keys" == "0" \
    || "$baseline_evicted_keys" != "$final_evicted_keys" ]]; then
  overall_passed=false
fi

echo "[soak] report: $REPORT_FILE"
if [[ "$overall_passed" != "true" ]]; then
  echo "[soak] acceptance failed" >&2
  exit 5
fi
echo "[soak] acceptance passed"

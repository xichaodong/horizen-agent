#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
ENDPOINTS_TEXT=${AGENT_CAPACITY_ENDPOINTS:-"http://127.0.0.1:8787 http://127.0.0.1:8788"}
LEVELS_TEXT=${AGENT_CAPACITY_LEVELS:-"10 25 50"}
SAMPLE_INTERVAL=${AGENT_CAPACITY_SAMPLE_INTERVAL:-0.25}
SAMPLE_COUNT=${AGENT_CAPACITY_SAMPLE_COUNT:-24}
REQUEST_TIMEOUT=${AGENT_CAPACITY_REQUEST_TIMEOUT:-30}
START_DISCONNECT_AFTER=${AGENT_CAPACITY_START_DISCONNECT_AFTER:-1}
REDIS_HOST=${AGENT_CAPACITY_REDIS_HOST:-127.0.0.1}
REDIS_PORT=${AGENT_CAPACITY_REDIS_PORT:-6379}
RUN_ID=${AGENT_CAPACITY_RUN_ID:-"$(date +%Y%m%d-%H%M%S)-$$"}
OUTPUT_DIR=${AGENT_CAPACITY_OUTPUT_DIR:-"$ROOT_DIR/target/capacity/$RUN_ID"}
REPORT_FILE=${AGENT_CAPACITY_REPORT:-"$OUTPUT_DIR/report.md"}

read -r -a ENDPOINTS <<< "$ENDPOINTS_TEXT"
read -r -a LEVELS <<< "$LEVELS_TEXT"

for command_name in curl jq redis-cli python3; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "[capacity] missing command: $command_name" >&2
    exit 2
  fi
done
if [[ ${#ENDPOINTS[@]} -lt 2 ]]; then
  echo "[capacity] at least two endpoints are required" >&2
  exit 2
fi

mkdir -p "$OUTPUT_DIR"

milliseconds() {
  python3 -c 'import time; print(int(time.time() * 1000))'
}

redis_value() {
  local section=$1
  local key=$2
  redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" --raw INFO "$section" \
    | tr -d '\r' | awk -F: -v wanted="$key" '$1 == wanted {print $2; exit}'
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
      trackedTurns: total(["leaseRenewal", "trackedTurns"]),
      databaseActiveConnections: total(["databasePool", "activeConnections"]),
      databaseAwaitingConnections: total(["databasePool", "threadsAwaitingConnection"]),
      heapUsedBytes: total(["process", "heapUsedBytes"]),
      liveThreads: total(["process", "liveThreads"])
    }'
}

sample_stage() {
  local destination=$1
  local sample_number
  local statuses
  for ((sample_number = 0; sample_number < SAMPLE_COUNT; sample_number++)); do
    if statuses=$(fetch_statuses 2>/dev/null); then
      printf '%s\n' "$statuses" | aggregate_statuses >> "$destination"
    fi
    sleep "$SAMPLE_INTERVAL"
  done
}

wait_for_recovery() {
  local attempt
  local statuses
  local outstanding
  for ((attempt = 0; attempt < 40; attempt++)); do
    statuses=$(fetch_statuses)
    outstanding=$(printf '%s\n' "$statuses" | aggregate_statuses | jq \
      '.activeConnections + .queuedEvents + .queuedBytes + .activePollers + .trackedTurns')
    if [[ "$outstanding" == "0" ]]; then
      return 0
    fi
    sleep 0.25
  done
  return 1
}

redis_version=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" --raw INFO server \
  | tr -d '\r' | awk -F: '$1 == "redis_version" {print $2; exit}')
if [[ -z "$redis_version" ]]; then
  echo "[capacity] Redis is unavailable at $REDIS_HOST:$REDIS_PORT" >&2
  exit 3
fi

for endpoint in "${ENDPOINTS[@]}"; do
  app_status=$(curl --fail --silent --show-error --max-time 3 "$endpoint/api/status")
  if ! jq -e '.ready == true and .modelName == "scripted-web"' \
      <<< "$app_status" >/dev/null; then
    echo "[capacity] endpoint must run ready SCRIPTED mode: $endpoint" >&2
    exit 3
  fi
  curl --fail --silent --show-error --max-time 3 "$endpoint/api/stream/status" >/dev/null
done

baseline_status=$(fetch_statuses | aggregate_statuses)
redis_memory_before=$(redis_value memory used_memory)
redis_clients_before=$(redis_value clients connected_clients)
redis_evicted_before=$(redis_value stats evicted_keys)
overall_failed=0

cat > "$REPORT_FILE" <<EOF
# SSE 双实例容量验收

- 运行标识：\`$RUN_ID\`
- Redis：\`$redis_version\`（\`$REDIS_HOST:$REDIS_PORT\`）
- 实例：\`$ENDPOINTS_TEXT\`
- 阶梯并发：\`$LEVELS_TEXT\`
- 场景：请求在一个实例启动，初始 SSE 于 ${START_DISCONNECT_AFTER}s 后断开，再由另一个实例从游标 0 订阅
- 恢复订阅超时：${REQUEST_TIMEOUT}s

| 并发 | HTTP 200 | 完整响应 | error | 耗时 ms | 峰值 SSE | 峰值轮询器 | 峰值队列字节 | 峰值 JDBC 活跃/等待 | 峰值堆内存 | 峰值线程 | 资源回落 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | :---: |
EOF

for level in "${LEVELS[@]}"; do
  stage_dir="$OUTPUT_DIR/level-$level"
  mkdir -p "$stage_dir"
  samples_file="$stage_dir/status.ndjson"
  : > "$samples_file"
  start_ms=$(milliseconds)
  start_pids=()
  session_ids=()
  destination_endpoints=()

  echo "[capacity] level=$level starting"
  sample_stage "$samples_file" &
  sampler_pid=$!

  for ((request_number = 0; request_number < level; request_number++)); do
    origin_index=$((request_number % ${#ENDPOINTS[@]}))
    destination_index=$(((origin_index + 1) % ${#ENDPOINTS[@]}))
    endpoint=${ENDPOINTS[$origin_index]}
    session_id="capacity-$RUN_ID-$level-$request_number"
    request_id="request-$RUN_ID-$level-$request_number"
    session_ids+=("$session_id")
    destination_endpoints+=("${ENDPOINTS[$destination_index]}")
    payload=$(jq -cn --arg session "$session_id" --arg request "$request_id" \
      --arg message "stream capacity $RUN_ID-$level-$request_number" \
      '{sessionId:$session, requestId:$request, message:$message, artifactIds:[]}')
    (
      curl --silent --max-time "$START_DISCONNECT_AFTER" \
        --output "$stage_dir/start-$request_number.sse" \
        -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
        --data "$payload" "$endpoint/api/chat/stream" \
        >/dev/null || true
    ) &
    start_pids+=("$!")
  done

  request_failures=0
  for request_pid in "${start_pids[@]}"; do
    if ! wait "$request_pid"; then
      request_failures=$((request_failures + 1))
    fi
  done

  subscribe_pids=()
  for ((request_number = 0; request_number < level; request_number++)); do
    turn_id=$({ awk '/^data:/{sub(/^data:/, ""); print; exit}' \
      "$stage_dir/start-$request_number.sse" || true; } | jq -r '.id // empty' 2>/dev/null || true)
    if [[ -z "$turn_id" ]]; then
      request_failures=$((request_failures + 1))
      : > "$stage_dir/response-$request_number.sse"
      echo 000 > "$stage_dir/http-$request_number.txt"
      continue
    fi
    subscribe_payload=$(jq -cn --arg session "${session_ids[$request_number]}" \
      --arg turn "$turn_id" \
      '{sessionId:$session, expectedTurnId:$turn, afterTimelineSequence:0, afterEventSequence:0}')
    (
      curl --silent --show-error --max-time "$REQUEST_TIMEOUT" \
        --output "$stage_dir/response-$request_number.sse" \
        --write-out '%{http_code}' \
        -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
        --data "$subscribe_payload" \
        "${destination_endpoints[$request_number]}/api/session/subscribe" \
        > "$stage_dir/http-$request_number.txt"
    ) &
    subscribe_pids+=("$!")
  done
  for request_pid in "${subscribe_pids[@]}"; do
    if ! wait "$request_pid"; then
      request_failures=$((request_failures + 1))
    fi
  done
  wait "$sampler_pid" || true
  elapsed_ms=$(( $(milliseconds) - start_ms ))

  http_ok=$(awk '$0 == "200" {count++} END {print count + 0}' "$stage_dir"/http-*.txt)
  done_count=$({ grep -l '^event:done' "$stage_dir"/response-*.sse 2>/dev/null || true; } \
    | wc -l | tr -d ' ')
  error_count=$({ grep -l '^event:error' "$stage_dir"/response-*.sse 2>/dev/null || true; } \
    | wc -l | tr -d ' ')
  complete_count=0
  for response_file in "$stage_dir"/response-*.sse; do
    turn_start_events=$(grep -c '^event:turn_start' "$response_file" || true)
    text_events=$(grep -c '^event:text_delta' "$response_file" || true)
    done_events=$(grep -c '^event:done' "$response_file" || true)
    if [[ "$turn_start_events" == "1" && "$text_events" == "3" && "$done_events" == "1" ]]; then
      complete_count=$((complete_count + 1))
    fi
  done
  if [[ ! -s "$samples_file" ]]; then
    echo "[capacity] no status samples at level=$level" >&2
    exit 4
  fi
  peaks=$(jq -s '{
      sse: (map(.activeConnections) | max),
      pollers: (map(.activePollers) | max),
      queuedBytes: (map(.queuedBytes) | max),
      databaseActive: (map(.databaseActiveConnections) | max),
      databaseAwaiting: (map(.databaseAwaitingConnections) | max),
      heap: (map(.heapUsedBytes) | max),
      threads: (map(.liveThreads) | max)
    }' "$samples_file")
  recovered=yes
  if ! wait_for_recovery; then
    recovered=no
  fi

  printf '| %s | %s | %s | %s | %s | %s | %s | %s | %s/%s | %s | %s | %s |\n' \
    "$level" "$http_ok" "$complete_count" "$error_count" "$elapsed_ms" \
    "$(jq -r .sse <<< "$peaks")" "$(jq -r .pollers <<< "$peaks")" \
    "$(jq -r .queuedBytes <<< "$peaks")" \
    "$(jq -r .databaseActive <<< "$peaks")" \
    "$(jq -r .databaseAwaiting <<< "$peaks")" "$(jq -r .heap <<< "$peaks")" \
    "$(jq -r .threads <<< "$peaks")" "$recovered" >> "$REPORT_FILE"

  if [[ "$http_ok" != "$level" || "$done_count" != "$level" \
      || "$complete_count" != "$level" \
      || "$error_count" != "0" || "$request_failures" != "0" || "$recovered" != "yes" ]]; then
    overall_failed=1
  fi
  echo "[capacity] level=$level http=$http_ok complete=$complete_count error=$error_count recovered=$recovered"
done

final_status=$(fetch_statuses | aggregate_statuses)
redis_memory_after=$(redis_value memory used_memory)
redis_clients_after=$(redis_value clients connected_clients)
redis_evicted_after=$(redis_value stats evicted_keys)
cat >> "$REPORT_FILE" <<EOF

## Redis 前后状态

| 指标 | 开始前 | 全部请求结束并回落后 | 差值 |
| --- | ---: | ---: | ---: |
| used_memory | $redis_memory_before | $redis_memory_after | $((redis_memory_after - redis_memory_before)) |
| connected_clients | $redis_clients_before | $redis_clients_after | $((redis_clients_after - redis_clients_before)) |
| evicted_keys | $redis_evicted_before | $redis_evicted_after | $((redis_evicted_after - redis_evicted_before)) |

Redis 的增量来自当前测试 Turn 的短期事件日志；它受单 Turn 容量上限约束，并按事件 TTL 自动过期，所以测试刚结束时
\`used_memory\` 不要求立即回到起点。\`connected_clients\` 和 \`evicted_keys\` 用于判断连接泄漏与内存淘汰。

## 实例资源回落

| 指标（两实例合计） | 开始前 | 全部请求结束后 |
| --- | ---: | ---: |
| activeConnections | $(jq -r .activeConnections <<< "$baseline_status") | $(jq -r .activeConnections <<< "$final_status") |
| queuedEvents | $(jq -r .queuedEvents <<< "$baseline_status") | $(jq -r .queuedEvents <<< "$final_status") |
| queuedBytes | $(jq -r .queuedBytes <<< "$baseline_status") | $(jq -r .queuedBytes <<< "$final_status") |
| activePollers | $(jq -r .activePollers <<< "$baseline_status") | $(jq -r .activePollers <<< "$final_status") |
| trackedTurns | $(jq -r .trackedTurns <<< "$baseline_status") | $(jq -r .trackedTurns <<< "$final_status") |
| JDBC active/awaiting | $(jq -r .databaseActiveConnections <<< "$baseline_status")/$(jq -r .databaseAwaitingConnections <<< "$baseline_status") | $(jq -r .databaseActiveConnections <<< "$final_status")/$(jq -r .databaseAwaitingConnections <<< "$final_status") |
| heapUsedBytes | $(jq -r .heapUsedBytes <<< "$baseline_status") | $(jq -r .heapUsedBytes <<< "$final_status") |
| liveThreads | $(jq -r .liveThreads <<< "$baseline_status") | $(jq -r .liveThreads <<< "$final_status") |

连接、发送队列、Redis 轮询器和租约跟踪都必须回到 0。JVM 堆由 GC 管理；Servlet、Reactor 与 JDBC
工作线程会作为有上限的共享线程池保留，以服务下一批请求。

原始 SSE 响应、HTTP 状态和采样数据位于 \`$OUTPUT_DIR\`。
EOF

echo "[capacity] report: $REPORT_FILE"
if [[ "$overall_failed" != "0" ]]; then
  echo "[capacity] acceptance failed" >&2
  exit 5
fi
echo "[capacity] acceptance passed"

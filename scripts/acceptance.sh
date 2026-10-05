#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
MODE=${1:-core}
cd "$ROOT_DIR"
case "$MODE" in
  core|live|real|workflow) ;;
  *) echo "usage: scripts/acceptance.sh [core|live|real|workflow]" >&2; exit 2 ;;
esac

echo "[acceptance] architecture boundaries"
scripts/check-architecture-boundaries.sh

echo "[acceptance] unit/integration verification"
./mvnw verify -q

echo "[acceptance] frontend recovery tests"
(cd horizen-agent-web && npm test)

echo "[acceptance] frontend production build"
(cd horizen-agent-web && npm run build)

if [[ "$MODE" == "core" ]]; then
  echo "[acceptance] core acceptance passed"
  exit 0
fi

if ! command -v redis-cli >/dev/null 2>&1 \
    || [[ "$(redis-cli -h 127.0.0.1 -p 6379 ping 2>/dev/null || true)" != "PONG" ]]; then
  echo "[acceptance] local Redis at 127.0.0.1:6379 is required" >&2
  exit 3
fi

if [[ "$MODE" == "workflow" ]]; then
  echo "[acceptance] real cloud workflow with human pauses, HTTP SSE disconnect and host restart"
  ./mvnw -q -pl horizen-agent-web -am \
    -Dhorizen.cloud.workflow.live=true \
    -Dtest=CloudWorkflowConfiguredLiveTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
  echo "[acceptance] workflow acceptance passed"
  exit 0
fi

echo "[acceptance] distributed pause/recovery/provider flows"
./mvnw -q -pl horizen-agent-web -am \
  -Dhorizen.redis.live=true \
  -Dtest=ApprovalDistributedFlowLiveTest,AskUserDistributedFlowLiveTest,HistoryRecoveryDistributedFlowLiveTest,RuntimeStorageLiveTest,ProviderPresentationDistributedFlowLiveTest,CrossInstanceInteractionRecoveryLiveTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

if [[ -f .env.yml ]]; then
  echo "[acceptance] configured MySQL/Redis schema"
  if [[ "${HORIZEN_ACCEPTANCE_APPLY_SCHEMA:-false}" == "true" ]]; then
    ./mvnw -q -pl horizen-agent-web -am \
      -Dhorizen.storage.live=true -Dhorizen.storage.apply-schema=true \
      -Dtest=RuntimeStorageConfiguredLiveTest \
      -Dsurefire.failIfNoSpecifiedTests=false test
  else
    ./mvnw -q -pl horizen-agent-web -am \
      -Dhorizen.storage.live=true \
      -Dtest=RuntimeStorageConfiguredLiveTest \
      -Dsurefire.failIfNoSpecifiedTests=false test
  fi
fi

if [[ ! -f .env.yml ]]; then
  echo "[acceptance] .env.yml is required for live E2B acceptance" >&2
  exit 4
fi

echo "[acceptance] E2B execution/isolation/cancellation/browser/process"
run_e2b_test() {
  local test_name=$1
  local attempt
  for attempt in 1 2; do
    if [[ "$test_name" == "E2bBrowserArtifactLiveTest" ]]; then
      ./mvnw -q -pl horizen-agent-web -am \
        -Dhorizen.e2b.browser.artifact.live=true \
        -Dtest="$test_name" -Dsurefire.failIfNoSpecifiedTests=false test && return 0
    elif ./mvnw -q -pl horizen-agent-web -am \
        -Dhorizen.e2b.browser.live=true \
        -Dtest="$test_name" -Dsurefire.failIfNoSpecifiedTests=false test; then
      return 0
    fi
    if [[ "$attempt" == "1" ]]; then
      echo "[acceptance] retrying $test_name after sandbox control-plane failure" >&2
      sleep 5
    fi
  done
  return 1
}

for e2b_test in E2bLiveSmokeTest E2bOwnerIsolationLiveTest E2bCancellationLiveTest E2bBrowserToolsLiveTest; do
  run_e2b_test "$e2b_test"
done

if [[ -f .env.yml ]]; then
  echo "[acceptance] browser screenshot -> JDBC/BOS -> vision URL"
  run_e2b_test E2bBrowserArtifactLiveTest
fi

if [[ "$MODE" == "real" ]]; then
  if [[ ! -f .env.yml ]]; then
    echo "[acceptance] .env.yml is required for real-model XLSX acceptance" >&2
    exit 5
  fi
  echo "[acceptance] real model XLSX upload -> sandbox edit -> BOS Artifact -> history/card recovery"
  ./mvnw -q -pl horizen-agent-web -am \
    -Dhorizen.real.xlsx.live=true \
    -Dtest=RealModelXlsxTaskLiveTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
fi

echo "[acceptance] $MODE acceptance passed"

#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT_DIR"

reject_imports() {
  local source_dir=$1
  local pattern=$2
  local layer=$3
  local matches
  matches=$(rg -n --glob '*.java' "$pattern" "$source_dir" || true)
  if [[ -n "$matches" ]]; then
    echo "[architecture] $layer has an outward dependency:" >&2
    echo "$matches" >&2
    exit 1
  fi
}

reject_imports horizen-agent-domain/src/main/java \
  '^import (io\.agentscope|reactor\.|redis\.clients|com\.baidubce|dev\.horizen\.agent\.(adapter|application|storage|web))' \
  domain

reject_imports horizen-agent-application/src/main/java \
  '^import (io\.agentscope|redis\.clients|com\.baidubce|dev\.horizen\.agent\.(adapter|storage|web|observability))' \
  application

reject_imports horizen-agent-runtime-api/src/main/java \
  '^import (io\.agentscope|dev\.horizen\.agent\.(adapter|web|storage))' runtime-api

reject_imports horizen-agent-tools/src/main/java \
  '^import dev\.horizen\.agent\.(sandbox\.e2b|storage|web)' tools

legacy=$(rg -n --glob '*.java' 'dev\.horizen\.agent\.skill\.horizen' . || true)
if [[ -n "$legacy" ]]; then
  echo "[architecture] legacy skill.horizen package remains:" >&2
  echo "$legacy" >&2
  exit 1
fi

echo "[architecture] dependency boundaries passed"

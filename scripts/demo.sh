#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT_DIR"
case "${1:-}" in
  "") ./mvnw --batch-mode --no-transfer-progress -pl horizen-agent-web -am package -DskipTests ;;
  --no-build) ;;
  *) echo "usage: scripts/demo.sh [--no-build]" >&2; exit 2 ;;
esac

shopt -s nullglob
jars=(horizen-agent-web/target/horizen-agent-web-*.jar)
if [[ ${#jars[@]} != 1 ]]; then
  echo "Expected one Web jar. Run ./mvnw clean package -DskipTests and retry." >&2
  exit 1
fi

echo "Starting the local scripted demo at http://127.0.0.1:${AGENT_WEB_PORT:-8787}"
jar_path="$ROOT_DIR/${jars[0]}"
demo_directory="${HORIZEN_DEMO_DIRECTORY:-$ROOT_DIR/target/local-demo}"
mkdir -p "$demo_directory"
cd "$demo_directory"
exec java -jar "$jar_path" \
  --horizen.local-config= \
  --spring.profiles.active=demo \
  --server.address=127.0.0.1 \
  --server.port="${AGENT_WEB_PORT:-8787}" \
  --horizen.agent.model-mode=SCRIPTED \
  --horizen.agent.storage.mode=LOCAL \
  --horizen.trace.enabled=false \
  --horizen.agent.gateway.mode=REMOTE \
  --horizen.agent.gateway.url= \
  --horizen.agent.sandbox.e2b.enabled=false \
  --horizen.agent.sandbox.snapshot.bos.enabled=false \
  --horizen.agent.artifact.bos.enabled=false \
  --horizen.agent.workspace-management.enabled=false \
  --horizen.agent.workspace-release.enabled=false \
  --horizen.agent.skill-release.enabled=false

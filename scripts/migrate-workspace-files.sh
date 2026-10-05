#!/usr/bin/env bash
set -euo pipefail
if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [config.yml]" >&2
  exit 2
fi
ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT_DIR"
./mvnw -q -pl horizen-agent-web -am -DskipTests package
./mvnw -q -pl horizen-agent-web -DskipTests \
  -Dexec.mainClass=dev.horizen.agent.web.bootstrap.workspace.WorkspaceFileMigrationCommand \
  -Dexec.args="${1:-.env.yml}" org.codehaus.mojo:exec-maven-plugin:3.5.0:java

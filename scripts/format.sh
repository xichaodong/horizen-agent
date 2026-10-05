#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT_DIR"
mode=${1:-check}
case "$mode" in
    apply)
        ./mvnw --batch-mode --no-transfer-progress spotless:apply
        python3 scripts/check-java-style.py
        npm --prefix horizen-agent-web run format
        ;;
    check)
        ./mvnw --batch-mode --no-transfer-progress spotless:check
        python3 scripts/check-java-style.py
        npm --prefix horizen-agent-web run format:check
        ;;
    *)
        echo "usage: scripts/format.sh [apply|check]" >&2
        exit 2
        ;;
esac

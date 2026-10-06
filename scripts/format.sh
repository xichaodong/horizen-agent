#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT_DIR"
mode=${1:-check}
case "$mode" in
    apply)
        python3 scripts/format-idea.py apply
        python3 scripts/check-java-style.py
        ;;
    check)
        python3 scripts/format-idea.py check
        python3 scripts/check-java-style.py
        ;;
    *)
        echo "usage: scripts/format.sh [apply|check]" >&2
        exit 2
        ;;
esac

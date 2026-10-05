#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
image=${1:-horizen-agent:local}
name="horizen-smoke-$$-$RANDOM"
configuration=$(mktemp -d)
cleanup() {
  docker rm -f "$name" >/dev/null 2>&1 || true
  rm -rf "$configuration"
}
trap cleanup EXIT

verify() {
  local address
  address=$(docker port "$name" 8787/tcp)
  if ! python3 "$ROOT_DIR/scripts/smoke-demo.py" --endpoint "http://$address" --frontend; then
    docker logs "$name" >&2
    return 1
  fi
  # Java 是容器主进程，启动完成后不再持有 root 身份。
  docker exec "$name" sh -c 'test "$(awk "/^Uid:/{print \$2}" /proc/1/status)" = 10001'
}

docker run --detach --name "$name" --publish 127.0.0.1::8787 "$image" >/dev/null
verify
docker rm -f "$name" >/dev/null

# 只挂载公开模板，验证权限为 0600 的配置可读；不读取真实本地配置。
cp "$ROOT_DIR/.env.yml.example" "$configuration/input.yml"
chmod 600 "$configuration/input.yml"
docker run --detach --name "$name" --publish 127.0.0.1::8787 \
  --mount "type=bind,source=$configuration/input.yml,target=/run/horizen-agent/input.yml,readonly" \
  "$image" configured >/dev/null
verify
echo 'Container smoke passed: bundled frontend, scripted SSE, private-permission template, non-root Java.'

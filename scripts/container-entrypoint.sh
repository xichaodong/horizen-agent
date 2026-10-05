#!/bin/sh
set -eu
umask 077

mode=${1:-demo}
if [ "$#" -gt 0 ]; then
    shift
fi

# 初始化数据目录后降权运行 Java；宿主的只读配置无需放宽文件权限。
chown 10001:10001 /var/lib/horizen-agent
case "$mode" in
    demo)
        exec gosu 10001:10001 java -jar /opt/horizen-agent/app.jar \
            --horizen.local-config= \
            --spring.profiles.active=demo \
            --server.address=0.0.0.0 \
            --server.port=8787 \
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
            --horizen.agent.skill-release.enabled=false "$@"
        ;;
    configured)
        if [ ! -f /run/horizen-agent/input.yml ]; then
            echo 'Mount .env.yml read-only at /run/horizen-agent/input.yml for configured mode.' >&2
            exit 2
        fi
        cp /run/horizen-agent/input.yml /run/horizen-agent/app.yml
        chown 10001:10001 /run/horizen-agent/app.yml
        chmod 600 /run/horizen-agent/app.yml
        exec gosu 10001:10001 java -jar /opt/horizen-agent/app.jar \
            --horizen.local-config=file:/run/horizen-agent/app.yml \
            --server.address=0.0.0.0 --server.port=8787 "$@"
        ;;
    *)
        echo 'Usage: horizen-agent [demo|configured] [Spring Boot arguments...]' >&2
        exit 2
        ;;
esac

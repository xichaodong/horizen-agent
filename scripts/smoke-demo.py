#!/usr/bin/env python3
"""用合成请求验证离线宿主、SSE 终态和可选的打包前端，无需服务凭据。"""
import argparse
from html.parser import HTMLParser
import http.client
import json
import os
from pathlib import Path
import socket
import shutil
import subprocess
import tempfile
import time
import traceback
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import uuid


class FrontendAssets(HTMLParser):
    """从入口 HTML 收集脚本、样式和图标，检查它们均由同一宿主提供。"""

    def __init__(self):
        super().__init__()
        self.assets = []
        self.has_root = False

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if values.get("id") == "root":
            self.has_root = True
        if tag == "script" and values.get("src"):
            self.assets.append((values["src"], "script"))
        if tag == "link" and values.get("rel") in ("stylesheet", "icon"):
            self.assets.append((values.get("href", ""), values["rel"]))


def verify_host(endpoint, frontend=False):
    """等待离线宿主就绪，再验证实际资源和一次完整的脚本模型回复。"""
    deadline = time.monotonic() + 45
    while True:
        try:
            with urllib.request.urlopen(endpoint + "/api/status", timeout=1) as response:
                status = json.load(response)
            break
        # 容器转发端口可能已接收连接，但 Java 尚未监听，连接会被关闭或重置。
        except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException):
            if time.monotonic() >= deadline:
                raise RuntimeError("Offline host did not become ready within 45 seconds.")
            time.sleep(.25)
    assert status["ready"] and status["modelName"] == "scripted-web", status["modelName"]
    assert not status["gateway"]["configured"]
    assert not status["sandbox"]["enabled"]
    assert not status["tracing"]["enabled"]
    if frontend:
        with urllib.request.urlopen(endpoint + "/", timeout=5) as response:
            assert response.headers.get_content_type() == "text/html"
            page = response.read().decode()
        assets = FrontendAssets()
        assets.feed(page)
        assert assets.has_root, "Missing frontend root"
        assert any(kind == "script" for _, kind in assets.assets), "Missing bundled script"
        assert any(kind == "stylesheet" for _, kind in assets.assets), "Missing bundled stylesheet"
        for path, kind in assets.assets:
            parsed = urlsplit(path)
            assert not parsed.scheme and not parsed.netloc, "External frontend dependency"
            assert path.startswith("/assets/"), "Frontend asset is not a production bundle"
            with urllib.request.urlopen(endpoint + path, timeout=5) as response:
                content_type = response.headers.get_content_type()
                assert response.read(), "Empty frontend asset"
                if kind == "script":
                    assert content_type in ("text/javascript", "application/javascript")
                elif kind == "stylesheet":
                    assert content_type == "text/css"
    payload = {"sessionId": "smoke-" + uuid.uuid4().hex, "message": "Hello, Horizen!"}
    request = urllib.request.Request(endpoint + "/api/chat/stream",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "Accept": "text/event-stream"})
    with urllib.request.urlopen(request, timeout=20) as response:
        stream = response.read().decode()
    events = [json.loads(line[5:].strip()) for line in stream.splitlines()
              if line.startswith("data:") and line[5:].strip()]
    assert any(event.get("type") == "done" for event in events), "No terminal SSE event"
    assert not any(event.get("type") == "error" for event in events), "Unexpected SSE error"
    text = "".join(event.get("text") or "" for event in events
                   if event.get("type") == "text_delta")
    assert "scripted:" in text and "Hello, Horizen!" in text, "Missing scripted reply"


def main():
    """区分外部容器验证与自启动验证，后者始终隔离真实本地配置。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--configuration-examples", action="store_true",
                        help="Copy the public YAML template and verify ordinary startup imports it.")
    parser.add_argument("--endpoint", help="Verify an already running offline host, such as a container.")
    parser.add_argument("--frontend", action="store_true", help="Require bundled production frontend assets.")
    options = parser.parse_args()
    if options.endpoint:
        if options.configuration_examples:
            parser.error("--endpoint cannot be combined with --configuration-examples")
        verify_host(options.endpoint.rstrip("/"), options.frontend)
        print("Offline Web smoke passed: existing host, bundled assets checked, scripted SSE completed."
              if options.frontend else "Offline Web smoke passed: existing host, scripted SSE completed.")
        return
    root = Path(__file__).resolve().parents[1]
    jars = list((root / "horizen-agent-web/target").glob("horizen-agent-web-*.jar"))
    if len(jars) != 1:
        raise RuntimeError("Build one Web jar with ./mvnw clean verify before this check.")
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    endpoint = f"http://127.0.0.1:{port}"
    env = {key: os.environ[key] for key in ("PATH", "JAVA_HOME", "HOME", "TMPDIR", "LANG")
           if key in os.environ}
    with tempfile.TemporaryDirectory(prefix="horizen-demo-smoke-") as directory:
        work = Path(directory)
        env.update({"HORIZEN_DEMO_DIRECTORY": str(work), "AGENT_WEB_PORT": str(port)})
        if options.configuration_examples:
            shutil.copyfile(root / ".env.yml.example", work / ".env.yml")
            command = ["java", "-jar", str(jars[0]), "--server.port=" + str(port)]
        else:
            env.update({
                "HORIZEN_TRACE_ENABLED": "true",
                "HORIZEN_TRACE_BASE_URL": "http://127.0.0.1:1",
                "HORIZEN_AGENT_STORAGE_MODE": "DISTRIBUTED",
                "HORIZEN_AGENT_GATEWAY_URL": "http://127.0.0.1:1"})
            # 演示入口必须忽略私有 YAML 配置。
            (work / ".env.yml").write_text("horizen: [invalid-yaml\n", encoding="utf-8")
            command = ["bash", str(root / "scripts/demo.sh"), "--no-build"]
        with (work / "host.log").open("wb") as log:
            process = subprocess.Popen(command, cwd=work, env=env,
                stdout=log, stderr=subprocess.STDOUT)
            try:
                verify_host(endpoint, options.frontend)
                scenario = "YAML template imported" if options.configuration_examples else "private config ignored"
                print(f"Offline Web smoke passed: {scenario}, scripted SSE reply completed.")
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    try:
        main()
    except Exception:
        if os.environ.get("GITHUB_ACTIONS") == "true":
            # 把实际失败断言附到检查结果，避免只留下退出码。
            detail = traceback.format_exc()[-5000:]
            detail = detail.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
            print('::error::Offline Web smoke failed: ' + detail, flush=True)
        raise

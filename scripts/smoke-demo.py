#!/usr/bin/env python3
"""Verify the packaged offline host with synthetic requests and no credentials."""
import argparse
import json
import os
from pathlib import Path
import socket
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--configuration-examples", action="store_true",
                        help="Copy the YAML template and verify ordinary startup imports them.")
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    jars = list((root / "horizen-agent-web/target").glob("horizen-agent-web-*.jar"))
    if len(jars) != 1:
        raise SystemExit("Build one Web jar with ./mvnw clean verify before this check.")
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
            (work / ".env.yml").write_text(
                "horizen: [invalid-yaml\n", encoding="utf-8")
            command = ["bash", str(root / "scripts/demo.sh"), "--no-build"]
        with (work / "host.log").open("wb") as log:
            process = subprocess.Popen(command, cwd=work, env=env,
                stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 45
                while True:
                    if process.poll() is not None:
                        raise RuntimeError("Offline host exited before becoming ready.")
                    try:
                        with urllib.request.urlopen(endpoint + "/api/status", timeout=1) as response:
                            status = json.load(response)
                        break
                    except (urllib.error.URLError, TimeoutError):
                        if time.monotonic() >= deadline:
                            raise RuntimeError("Offline host did not become ready within 45 seconds.")
                        time.sleep(.25)
                assert status["ready"] and status["modelName"] == "scripted-web", status["modelName"]
                assert not status["gateway"]["configured"]
                assert not status["sandbox"]["enabled"]
                assert not status["tracing"]["enabled"]
                payload = {"sessionId": "smoke-" + uuid.uuid4().hex,
                           "message": "Hello, Horizen!"}
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
    main()

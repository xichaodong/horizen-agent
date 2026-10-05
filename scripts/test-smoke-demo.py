#!/usr/bin/env python3
"""通过真实 HTTP 连接验证容器启动期间的临时断连不会让就绪检查提前失败。"""
import importlib.util
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import threading
import unittest

spec = importlib.util.spec_from_file_location("smoke_demo", Path(__file__).with_name("smoke-demo.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class StartupReadinessTest(unittest.TestCase):
    def test_closed_startup_connections_retry_until_the_host_is_ready(self):
        class Handler(BaseHTTPRequestHandler):
            attempts = 0

            def do_GET(self):
                Handler.attempts += 1
                if Handler.attempts <= 2:
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.close_connection = True
                    return
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({
                    "ready": True, "modelName": "scripted-web", "gateway": {"configured": False},
                    "sandbox": {"enabled": False}, "tracing": {"enabled": False}
                }).encode())

            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", "0")))
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.end_headers()
                events = [{"type": "text_delta", "text": "scripted: Hello, Horizen!"}, {"type": "done"}]
                for event in events:
                    self.wfile.write(("data: " + json.dumps(event) + "\n\n").encode())

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            smoke.verify_host(f"http://127.0.0.1:{server.server_port}")
            self.assertEqual(3, Handler.attempts)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()

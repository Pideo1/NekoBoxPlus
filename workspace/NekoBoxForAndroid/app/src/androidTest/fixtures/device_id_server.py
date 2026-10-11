#!/usr/bin/env python3
"""Loopback capture server for SubscriptionDeviceAvdTest, run on the AVD host.

Start this script, then run connectedOssDebugAndroidTest with these Gradle flags:
  -Pandroid.testInstrumentationRunnerArguments.class=io.nekohasekai.sagernet.ui.SubscriptionDeviceAvdTest
  -Pandroid.testInstrumentationRunnerArguments.deviceIdServer=http://10.0.2.2:18765

The suite uses synthetic subscriptions. Request captures stay in /tmp.
"""

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

CAPTURE_FILE = Path("/tmp/nb4a-hwid-requests.jsonl")
RECORDS = []
LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        url = urlsplit(self.path)
        response_headers = {}
        if url.path == "/records":
            path = parse_qs(url.query).get("path", [""])[0]
            with LOCK:
                body = json.dumps([
                    record for record in RECORDS
                    if not path or record["path"] == path
                ]).encode()
            response_headers["Content-Type"] = "application/json"
        elif url.path == "/health":
            body = b"OK"
        else:
            record = {
                "path": url.path,
                "headers": {key.lower(): value for key, value in self.headers.items()},
                "time": time.time(),
            }
            with LOCK:
                RECORDS.append(record)
                with CAPTURE_FILE.open("a") as capture:
                    capture.write(json.dumps(record) + "\n")
            response_headers["Content-Type"] = "text/plain; charset=utf-8"
            if url.path.startswith("/valid/"):
                body = b"ss://YWVzLTEyOC1nY206bG9jYWwtdGVzdA@127.0.0.1:9#Local%20AVD%20test\n"
                response_headers["subscription-userinfo"] = "upload=0; download=123; total=456"
                response_headers["profile-title"] = "Local AVD test"
            else:
                body = b""
        self.send_response(200)
        for name, value in response_headers.items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    CAPTURE_FILE.write_text("")
    with ThreadingHTTPServer(("127.0.0.1", 18765), Handler) as server:
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass

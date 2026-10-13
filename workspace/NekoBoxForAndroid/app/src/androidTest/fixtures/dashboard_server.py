#!/usr/bin/env python3
"""Deterministic HTTP fixtures; pair with a loopback sing-box SOCKS server.

Run: python3 app/src/androidTest/fixtures/dashboard_server.py
In another terminal, use the local sing-box binary:
    sing-box run -c app/src/androidTest/fixtures/dashboard-sing-box.json
Then run connectedOssDebugAndroidTest with DashboardAvdTest,
DashboardAuthAvdTest and DashboardThemeAvdTest selected.
The emulator reaches both listeners through 10.0.2.2.
"""
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        pass

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        if self.path == "/disconnect":
            self.close_connection = True
            return
        if self.path == "/auth":
            self.send_response(302)
            self.send_header("Location", "/auth-finished")
            self.send_header("Set-Cookie", "dashboard-test-session=fixture; Path=/; HttpOnly")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if self.path == "/generate_204":
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if self.path == "/timeout":
            time.sleep(3)
        if self.path == "/delay":
            time.sleep(0.25)
        data = b"OK" if self.path == "/health" else b"dashboard-test\n" * 8192
        if self.path == "/slow":
            data = b"x" * 65536
        count = 100 if self.path == "/slow" else 1
        self.send_response(503 if self.path == "/fail" else 200)
        self.send_header("Content-Length", str(len(data) * count))
        self.end_headers()
        if self.command == "HEAD":
            return
        try:
            for _ in range(count):
                self.wfile.write(data)
                self.wfile.flush()
                if count > 1:
                    time.sleep(0.1)
        except (BrokenPipeError, ConnectionResetError):
            pass


if __name__ == "__main__":
    for port in (18765, 18766):
        server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
    print("Dashboard HTTP fixtures ready on 127.0.0.1:18765 and :18766", flush=True)
    threading.Event().wait()
